package com.example.mediaupload.consumer;

import com.example.mediaupload.config.LoadFile;
import com.example.mediaupload.grpc.ActionReply;
import com.example.mediaupload.grpc.BeginUploadReply;
import com.example.mediaupload.grpc.BeginUploadRequest;
import com.example.mediaupload.grpc.UploadReply;
import com.example.mediaupload.grpc.UploadStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Owns upload admission, the bounded FIFO queue, duplicate/idempotency indexes,
 * temporary files, and the final video catalogue.
 */
public final class UploadCoordinator {
    private static final List<String> ALLOWED_EXTENSIONS = List.of("mp4", "m4v", "mov", "mkv", "avi");

    private final Object stateLock = new Object();
    private final LoadFile.Config config;
    private final Path rootDirectory;
    private final Path videosDirectory;
    private final Path temporaryDirectory;
    private final Path metadataDirectory;
    private final BlockingQueue<UploadJob> queue;
    // A permit is held from BeginUpload until a worker takes the job.
    // This lets the server tell the producer that the queue is full BEFORE bytes are sent.
    private final Semaphore queueSlots;
    private final Map<String, UploadEntry> uploads = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, StoredVideo> videos = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> sha1ToVideoId = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> sha1Reservations = new java.util.concurrent.ConcurrentHashMap<>();

    public UploadCoordinator(LoadFile.Config config) throws IOException {
        this.config = config;
        this.rootDirectory = config.storageDir().toAbsolutePath().normalize();
        this.videosDirectory = rootDirectory.resolve("videos");
        this.temporaryDirectory = rootDirectory.resolve(".incoming");
        this.metadataDirectory = rootDirectory.resolve("metadata");
        Files.createDirectories(videosDirectory);
        Files.createDirectories(temporaryDirectory);
        Files.createDirectories(metadataDirectory);
        this.queue = new ArrayBlockingQueue<>(config.q());
        this.queueSlots = new Semaphore(config.q(), true);
        loadMetadataAndCleanOldTemporaryFiles();
    }

    /** Called before a producer streams data. It reserves capacity or replies QUEUE_FULL. */
    public BeginUploadReply beginUpload(BeginUploadRequest request) {
        String uploadId = request.getUploadId().trim();
        String fileName = safeFileName(request.getOriginalFilename());
        String sha1 = request.getSha1().trim().toLowerCase(Locale.ROOT);
        long fileSize = request.getFileSize();

        if (!uploadId.matches("[A-Za-z0-9_-]{8,100}")) {
            return beginReply(UploadStatus.INVALID_REQUEST, "upload_id must contain 8-100 safe characters.", "", "");
        }
        if (fileName.isBlank() || !isSupported(fileName)) {
            return beginReply(UploadStatus.INVALID_REQUEST, "Unsupported video format. Use mp4, m4v, mov, mkv, or avi.", "", "");
        }
        if (!sha1.matches("[0-9a-f]{40}")) {
            return beginReply(UploadStatus.INVALID_REQUEST, "A 40-character SHA-1 hash is required.", "", "");
        }
        if (fileSize <= 0 || fileSize > config.maxVideoBytes()) {
            return beginReply(UploadStatus.INVALID_REQUEST, "File is empty or exceeds the configured size limit.", "", "");
        }

        synchronized (stateLock) {
            UploadEntry previousAttempt = uploads.get(uploadId);
            if (previousAttempt != null) {
                if (!previousAttempt.sha1.equals(sha1)) {
                    return beginReply(UploadStatus.IDEMPOTENCY_CONFLICT,
                            "This upload_id was already used with different content.", previousAttempt.videoId, "");
                }
                if (previousAttempt.status == UploadStatus.FAILED) {
                    uploads.remove(uploadId);
                } else if (previousAttempt.status == UploadStatus.RECEIVING) {
                    // A BeginUpload response or an earlier stream may have been lost.
                    // Rotate the stream token and restart the temporary file from byte 0.
                    // Old gRPC stream callbacks carry the old token and cannot abort this retry.
                    try {
                        synchronized (previousAttempt) {
                            closeQuietly(previousAttempt.output);
                            deleteQuietly(previousAttempt.temporaryFile);
                            previousAttempt.temporaryFile = temporaryDirectory.resolve(UUID.randomUUID() + ".part");
                            previousAttempt.output = Files.newOutputStream(previousAttempt.temporaryFile);
                            previousAttempt.bytesWritten = 0;
                            previousAttempt.nextChunkIndex = 0;
                            previousAttempt.streamToken = UUID.randomUUID().toString();
                        }
                        return beginReply(UploadStatus.ACCEPTED,
                                "Retry recognized. Previous partial bytes were cleared; stream the same file again.",
                                previousAttempt.videoId, "", previousAttempt.streamToken);
                    } catch (IOException e) {
                        previousAttempt.status = UploadStatus.FAILED;
                        uploads.remove(uploadId);
                        sha1Reservations.remove(sha1, uploadId);
                        queueSlots.release();
                        return beginReply(UploadStatus.FAILED, "Could not restart temporary upload: " + e.getMessage(), "", "");
                    }
                } else {
                    return existingAttemptReply(previousAttempt);
                }
            }

            String storedVideoId = sha1ToVideoId.get(sha1);
            if (storedVideoId != null) {
                return beginReply(UploadStatus.DUPLICATE_CONTENT,
                        "The same SHA-1 content is already stored.", storedVideoId, "");
            }
            String activeUploadId = sha1Reservations.get(sha1);
            if (activeUploadId != null && !activeUploadId.equals(uploadId)) {
                return beginReply(UploadStatus.DUPLICATE_IN_PROGRESS,
                        "The same content is already uploading or waiting for processing.", "", activeUploadId);
            }

            if (!queueSlots.tryAcquire()) {
                return beginReply(UploadStatus.QUEUE_FULL,
                        "Queue is full. This upload is rejected before its bytes are sent.", "", "");
            }

            String videoId = UUID.randomUUID().toString();
            Path temporaryFile = temporaryDirectory.resolve(UUID.randomUUID() + ".part");
            try {
                OutputStream output = Files.newOutputStream(temporaryFile);
                String streamToken = UUID.randomUUID().toString();
                UploadEntry entry = new UploadEntry(uploadId, fileName, sha1, fileSize,
                        videoId, temporaryFile, output, streamToken);
                uploads.put(uploadId, entry);
                sha1Reservations.put(sha1, uploadId);
                System.out.printf("[Consumer] ADMITTED %s; reserved one of %d queue slots.%n", fileName, config.q());
                return beginReply(UploadStatus.ACCEPTED, "Queue slot reserved. Start streaming the file.", videoId, "", streamToken);
            } catch (IOException e) {
                queueSlots.release();
                return beginReply(UploadStatus.FAILED, "Could not create a temporary upload file: " + e.getMessage(), "", "");
            }
        }
    }

    /** Writes one ordered gRPC chunk to the temporary file for this upload. */
    public void appendChunk(String uploadId, String streamToken, int chunkIndex, byte[] bytes) throws IOException {
        UploadEntry entry;
        synchronized (stateLock) {
            entry = uploads.get(uploadId);
            if (entry == null || entry.status != UploadStatus.RECEIVING || !entry.streamToken.equals(streamToken)) {
                throw new IOException("No active receiving session for this upload_id/stream token.");
            }
        }
        synchronized (entry) {
            // Recheck after acquiring the per-upload lock: a retry may have rotated the token.
            if (entry.status != UploadStatus.RECEIVING || !entry.streamToken.equals(streamToken)) {
                throw new IOException("This stream was superseded by a retry or cancellation.");
            }
            if (chunkIndex != entry.nextChunkIndex) {
                throw new IOException("Out-of-order chunk. Expected " + entry.nextChunkIndex + " but received " + chunkIndex);
            }
            if (entry.bytesWritten + bytes.length > entry.expectedSize
                    || entry.bytesWritten + bytes.length > config.maxVideoBytes()) {
                throw new IOException("Upload exceeded the declared/configured file size.");
            }
            entry.output.write(bytes);
            entry.bytesWritten += bytes.length;
            entry.nextChunkIndex++;
        }
    }

    /** Closes, verifies and enqueues the complete upload. */
    public UploadReply finishUpload(String uploadId, String streamToken) {
        UploadEntry entry;
        synchronized (stateLock) {
            entry = uploads.get(uploadId);
            if (entry == null || entry.status != UploadStatus.RECEIVING || !entry.streamToken.equals(streamToken)) {
                return uploadReply(UploadStatus.FAILED, "Upload session is no longer receiving data for this stream token.", "");
            }
        }

        Path receivedFile;
        long receivedBytes;
        try {
            synchronized (entry) {
                if (entry.status != UploadStatus.RECEIVING || !entry.streamToken.equals(streamToken)) {
                    return uploadReply(UploadStatus.CANCELLED, "Upload was cancelled or superseded by a retry.", "");
                }
                if (entry.output == null) {
                    return uploadReply(UploadStatus.FAILED, "Upload stream was already closed.", "");
                }
                entry.output.close();
                entry.output = null;
                receivedFile = entry.temporaryFile;
                receivedBytes = entry.bytesWritten;
            }

            long actualSize = Files.size(receivedFile);
            if (actualSize != entry.expectedSize || receivedBytes != entry.expectedSize) {
                abortReceiving(uploadId, streamToken, "Received size did not match the declared size.");
                return uploadReply(UploadStatus.FAILED, "Received size did not match the declared size.", "");
            }
            String actualSha1 = sha1(receivedFile);
            if (!actualSha1.equals(entry.sha1)) {
                abortReceiving(uploadId, streamToken, "SHA-1 verification failed.");
                return uploadReply(UploadStatus.FAILED, "SHA-1 verification failed. Upload discarded.", "");
            }

            synchronized (stateLock) {
                if (entry.status != UploadStatus.RECEIVING || !entry.streamToken.equals(streamToken)
                        || !receivedFile.equals(entry.temporaryFile)) {
                    return uploadReply(UploadStatus.CANCELLED, "Upload was cancelled or superseded by a retry.", "");
                }
                entry.status = UploadStatus.QUEUED;
                UploadJob job = new UploadJob(entry);
                entry.job = job;
                if (!queue.offer(job)) {
                    // The semaphore should make this impossible; retain the guard in case code changes.
                    entry.status = UploadStatus.FAILED;
                    uploads.remove(uploadId);
                    sha1Reservations.remove(entry.sha1, uploadId);
                    queueSlots.release();
                    deleteQuietly(receivedFile);
                    return uploadReply(UploadStatus.FAILED, "Queue admission failed unexpectedly.", "");
                }
            }
            System.out.printf("[Consumer] QUEUED %s (upload_id=%s). Waiting=%d%n",
                    entry.originalName, uploadId, queue.size());
            return uploadReply(UploadStatus.QUEUED, "Upload received and added to the processing queue.", entry.videoId);
        } catch (IOException e) {
            abortReceiving(uploadId, streamToken, e.getMessage());
            return uploadReply(UploadStatus.FAILED, "Could not verify upload: " + e.getMessage(), "");
        }
    }

    /** gRPC stream failure or client disconnect: discard the incomplete temporary file. */
    public void abortReceiving(String uploadId, String streamToken, String reason) {
        UploadEntry entry;
        synchronized (stateLock) {
            entry = uploads.get(uploadId);
            if (entry == null || entry.status != UploadStatus.RECEIVING || !entry.streamToken.equals(streamToken)) return;
            entry.status = UploadStatus.FAILED;
            sha1Reservations.remove(entry.sha1, uploadId);
            queueSlots.release();
        }
        synchronized (entry) {
            closeQuietly(entry.output);
            entry.output = null;
            deleteQuietly(entry.temporaryFile);
        }
        System.err.printf("[Consumer] Incomplete upload %s discarded: %s%n", uploadId, reason);
    }

    /** Cancels a queued job. A job already claimed by a worker cannot be cancelled this way. */
    public ActionReply cancelUpload(String uploadId) {
        UploadEntry entry;
        synchronized (stateLock) {
            entry = uploads.get(uploadId);
            if (entry == null) return action(false, "Unknown upload_id.");
            if (entry.status == UploadStatus.RECEIVING) {
                entry.status = UploadStatus.CANCELLED;
                sha1Reservations.remove(entry.sha1, uploadId);
                queueSlots.release();
                synchronized (entry) {
                    closeQuietly(entry.output);
                    entry.output = null;
                    deleteQuietly(entry.temporaryFile);
                }
                return action(true, "Receiving upload cancelled and temporary file removed.");
            }
            if (entry.status != UploadStatus.QUEUED || entry.job == null) {
                return action(false, "Upload is not waiting in the queue; it may already be processing or finished.");
            }
            // If remove() is false, a worker has already taken the job. Do not pretend it was cancelled.
            if (!queue.remove(entry.job)) {
                return action(false, "A worker has already picked up this upload; cancellation is too late.");
            }
            entry.status = UploadStatus.CANCELLED;
            sha1Reservations.remove(entry.sha1, uploadId);
            queueSlots.release();
        }
        deleteQuietly(entry.temporaryFile);
        System.out.printf("[Consumer] CANCELLED queued upload %s; temporary file removed.%n", uploadId);
        return action(true, "Queued upload cancelled and temporary file removed.");
    }

    /** Worker waits for the next FIFO job and atomically changes QUEUED -> PROCESSING. */
    public UploadEntry takeNextJob() throws InterruptedException {
        UploadJob job = queue.take();
        queueSlots.release();
        synchronized (stateLock) {
            if (job.entry.status != UploadStatus.QUEUED) return null;
            job.entry.status = UploadStatus.PROCESSING;
            return job.entry;
        }
    }

    /** Called by a worker after FFmpeg has produced the normalized MP4. */
    public void markStored(UploadEntry entry, Path compressedFile, int workerNumber) throws IOException {
        Path destination = videosDirectory.resolve(entry.videoId + ".mp4");
        try {
            Files.move(compressedFile, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(compressedFile, destination, StandardCopyOption.REPLACE_EXISTING);
        }

        StoredVideo stored = new StoredVideo(entry.videoId, entry.uploadId, entry.originalName,
                entry.sha1, destination.getFileName().toString(), java.time.Instant.now().toString());
        try {
            synchronized (stateLock) {
                writeMetadata(stored, "STORED");
                entry.status = UploadStatus.STORED;
                videos.put(stored.videoId(), stored);
                sha1ToVideoId.put(stored.sha1(), stored.videoId());
                sha1Reservations.remove(entry.sha1, entry.uploadId);
            }
        } catch (IOException e) {
            deleteQuietly(destination);
            throw e;
        }
        deleteQuietly(entry.temporaryFile);
        entry.temporaryFile = null;
        System.out.printf("[Worker %d] STORED %s as %s%n", workerNumber, entry.originalName, stored.storedFile());
    }

    /** Worker failure: remove temp data and make the same upload_id eligible for a fresh retry. */
    public void markFailed(UploadEntry entry, String reason, int workerNumber) {
        synchronized (stateLock) {
            entry.status = UploadStatus.FAILED;
            sha1Reservations.remove(entry.sha1, entry.uploadId);
        }
        deleteQuietly(entry.temporaryFile);
        System.err.printf("[Worker %d] FAILED %s: %s%n", workerNumber, entry.originalName, reason);
    }

    public List<StoredVideo> listVideos() {
        return videos.values().stream()
                .sorted(Comparator.comparing(StoredVideo::storedAt).reversed())
                .toList();
    }

    public List<UploadSnapshot> listActiveUploads() {
        return uploads.values().stream()
                .filter(entry -> entry.status == UploadStatus.RECEIVING
                        || entry.status == UploadStatus.QUEUED
                        || entry.status == UploadStatus.PROCESSING)
                .map(entry -> new UploadSnapshot(entry.uploadId, entry.originalName,
                        entry.status.name(), entry.videoId))
                .sorted(Comparator.comparing(UploadSnapshot::uploadId))
                .toList();
    }

    public StoredVideo findVideo(String videoId) {
        return videos.get(videoId);
    }

    public Path getVideoPath(String videoId) {
        StoredVideo video = videos.get(videoId);
        return video == null ? null : videosDirectory.resolve(video.storedFile()).normalize();
    }

    /** Deletes the compressed file and removes it from the gallery and SHA-1 index. */
    public ActionReply deleteVideo(String videoId) {
        StoredVideo stored;
        synchronized (stateLock) {
            stored = videos.get(videoId);
            if (stored == null) return action(false, "Video was not found.");
            Path videoPath = videosDirectory.resolve(stored.storedFile()).normalize();
            if (!videoPath.getParent().equals(videosDirectory)) return action(false, "Invalid stored path.");
            try {
                Files.deleteIfExists(videoPath);
                videos.remove(videoId);
                sha1ToVideoId.remove(stored.sha1(), videoId);
                UploadEntry entry = uploads.get(stored.uploadId());
                if (entry != null) entry.status = UploadStatus.CANCELLED;
                writeMetadata(stored, "DELETED");
                return action(true, "Video deleted from storage and removed from the gallery.");
            } catch (IOException e) {
                return action(false, "Could not delete video: " + e.getMessage());
            }
        }
    }

    public int waitingCount() { return queue.size(); }
    public int queueCapacity() { return config.q(); }

    /** Values displayed by the browser UI; loaded from the consumer config.properties. */
    public ConfigSummary getConfigSummary() {
        return new ConfigSummary(config.p(), config.c(), config.q());
    }

    private BeginUploadReply existingAttemptReply(UploadEntry entry) {
        return switch (entry.status) {
            case RECEIVING -> beginReply(UploadStatus.ALREADY_RECEIVING, "This upload_id is already receiving data.", entry.videoId, "");
            case QUEUED -> beginReply(UploadStatus.ALREADY_QUEUED, "This upload_id is already queued; no second copy was created.", entry.videoId, "");
            case PROCESSING -> beginReply(UploadStatus.ALREADY_PROCESSING, "This upload_id is already being processed.", entry.videoId, "");
            case STORED -> beginReply(UploadStatus.ALREADY_SAVED, "This upload_id has already been saved.", entry.videoId, "");
            case CANCELLED -> beginReply(UploadStatus.CANCELLED, "This upload attempt was cancelled.", entry.videoId, "");
            default -> beginReply(UploadStatus.FAILED, "This upload attempt failed.", entry.videoId, "");
        };
    }

    private void loadMetadataAndCleanOldTemporaryFiles() throws IOException {
        try (var paths = Files.list(metadataDirectory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".properties")).forEach(path -> {
                Properties properties = new Properties();
                try (var input = Files.newInputStream(path)) {
                    properties.load(input);
                    String videoId = properties.getProperty("videoId", "");
                    String uploadId = properties.getProperty("uploadId", "");
                    String fileName = properties.getProperty("originalName", "");
                    String sha1 = properties.getProperty("sha1", "");
                    String storedFile = properties.getProperty("storedFile", "");
                    String storedAt = properties.getProperty("storedAt", "");
                    String status = properties.getProperty("status", "DELETED");
                    if (videoId.isBlank() || uploadId.isBlank() || !sha1.matches("[0-9a-f]{40}")) return;

                    UploadEntry restored = new UploadEntry(uploadId, fileName, sha1, 0,
                            videoId, null, null, "");
                    if ("STORED".equals(status) && Files.isRegularFile(videosDirectory.resolve(storedFile))) {
                        restored.status = UploadStatus.STORED;
                        StoredVideo video = new StoredVideo(videoId, uploadId, fileName, sha1, storedFile, storedAt);
                        videos.put(videoId, video);
                        sha1ToVideoId.put(sha1, videoId);
                    } else {
                        restored.status = UploadStatus.CANCELLED;
                    }
                    uploads.put(uploadId, restored);
                } catch (IOException e) {
                    System.err.println("[Consumer] Could not read metadata " + path + ": " + e.getMessage());
                }
            });
        }
        // A restart discards unfinished sessions and queued temp files; final stored files remain.
        try (var paths = Files.list(temporaryDirectory)) {
            paths.forEach(this::deleteQuietly);
        }
    }

    private void writeMetadata(StoredVideo stored, String status) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("videoId", stored.videoId());
        properties.setProperty("uploadId", stored.uploadId());
        properties.setProperty("originalName", stored.originalName());
        properties.setProperty("sha1", stored.sha1());
        properties.setProperty("storedFile", "STORED".equals(status) ? stored.storedFile() : "");
        properties.setProperty("storedAt", stored.storedAt());
        properties.setProperty("status", status);
        Path destination = metadataDirectory.resolve(stored.videoId() + ".properties");
        Path temporary = metadataDirectory.resolve(stored.videoId() + ".properties.tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "Media upload metadata");
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static BeginUploadReply beginReply(UploadStatus status, String message, String videoId, String relatedId) {
        return beginReply(status, message, videoId, relatedId, "");
    }

    private static BeginUploadReply beginReply(UploadStatus status, String message, String videoId,
                                               String relatedId, String streamToken) {
        return BeginUploadReply.newBuilder().setStatus(status).setMessage(message)
                .setVideoId(videoId).setRelatedUploadId(relatedId).setStreamToken(streamToken).build();
    }

    private static UploadReply uploadReply(UploadStatus status, String message, String videoId) {
        return UploadReply.newBuilder().setStatus(status).setMessage(message).setVideoId(videoId).build();
    }

    private static ActionReply action(boolean success, String message) {
        return ActionReply.newBuilder().setSuccess(success).setMessage(message).build();
    }

    private static String safeFileName(String original) {
        if (original == null) return "";
        String normalized = original.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return normalized.substring(slash + 1).trim();
    }

    private static boolean isSupported(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return ALLOWED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by this exercise", e);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); }
        catch (IOException e) { System.err.println("[Consumer] Could not clean " + path + ": " + e.getMessage()); }
    }

    private static void closeQuietly(OutputStream output) {
        if (output == null) return;
        try { output.close(); } catch (IOException ignored) { /* Best-effort cleanup. */ }
    }

    public static final class UploadEntry {
        public final String uploadId;
        public final String originalName;
        public final String sha1;
        public final long expectedSize;
        public final String videoId;
        public volatile Path temporaryFile;
        public volatile OutputStream output;
        public volatile UploadStatus status = UploadStatus.RECEIVING;
        public volatile String streamToken;
        public long bytesWritten;
        public int nextChunkIndex;
        public UploadJob job;

        private UploadEntry(String uploadId, String originalName, String sha1, long expectedSize,
                            String videoId, Path temporaryFile, OutputStream output, String streamToken) {
            this.uploadId = uploadId;
            this.originalName = originalName;
            this.sha1 = sha1;
            this.expectedSize = expectedSize;
            this.videoId = videoId;
            this.temporaryFile = temporaryFile;
            this.output = output;
            this.streamToken = streamToken;
        }
    }

    private record UploadJob(UploadEntry entry) { }
    public record StoredVideo(String videoId, String uploadId, String originalName,
                              String sha1, String storedFile, String storedAt) { }
    public record UploadSnapshot(String uploadId, String originalName, String status, String videoId) { }
    public record ConfigSummary(int producerThreads, int consumerThreads, int maxQueueLength) { }
}
