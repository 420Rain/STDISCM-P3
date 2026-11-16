package com.example.mediaupload.producer;

import com.example.mediaupload.config.LoadFile;
import com.example.mediaupload.grpc.BeginUploadReply;
import com.example.mediaupload.grpc.BeginUploadRequest;
import com.example.mediaupload.grpc.CancelUploadRequest;
import com.example.mediaupload.grpc.MediaUploadServiceGrpc;
import com.example.mediaupload.grpc.UploadReply;
import com.example.mediaupload.grpc.UploadStatus;
import com.example.mediaupload.grpc.VideoChunk;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;
import java.util.stream.Stream;

/** One producer thread. It reads only videos/producer-N. */
final class ProducerWorker implements Runnable {
    private static final String[] ALLOWED_EXTENSIONS = {"mp4", "m4v", "mov", "mkv", "avi"};
    private final int producerNumber;
    private final LoadFile.Config config;

    ProducerWorker(int producerNumber, LoadFile.Config config) {
        this.producerNumber = producerNumber;
        this.config = config;
    }

    @Override
    public void run() {
        Path folder = config.producerInputDir().resolve("producer-" + producerNumber);
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(config.consumerHost(), config.grpcPort())
                .usePlaintext() // Demo setup only. Use TLS/authentication outside a trusted lab network.
                .build();
        MediaUploadServiceGrpc.MediaUploadServiceBlockingStub blockingStub =
                MediaUploadServiceGrpc.newBlockingStub(channel);
        MediaUploadServiceGrpc.MediaUploadServiceStub asyncStub =
                MediaUploadServiceGrpc.newStub(channel);

        try {
            Files.createDirectories(folder);
            System.out.printf("[Producer %d] Reading %s%n", producerNumber, folder.toAbsolutePath());
            try (Stream<Path> paths = Files.list(folder)) {
                paths.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(path -> processFile(path, blockingStub, asyncStub));
            }
        } catch (IOException e) {
            System.err.printf("[Producer %d] Folder error: %s%n", producerNumber, e.getMessage());
        } finally {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) channel.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }

    private void processFile(Path file,
                             MediaUploadServiceGrpc.MediaUploadServiceBlockingStub blockingStub,
                             MediaUploadServiceGrpc.MediaUploadServiceStub asyncStub) {
        String fileName = file.getFileName().toString();
        if (!isSupported(fileName)) {
            log("SKIPPED unsupported file: " + fileName);
            return;
        }
        if (!Files.isRegularFile(file)) {
            log("SKIPPED because the source was deleted before upload: " + fileName);
            return;
        }

        try {
            long fileSize = Files.size(file);
            if (fileSize <= 0 || fileSize > config.maxVideoBytes()) {
                log("SKIPPED invalid/too-large file: " + fileName + " (" + fileSize + " bytes)");
                return;
            }
            String sha1 = sha1(file);
            String uploadId = UUID.randomUUID().toString();
            uploadWithRetry(file, fileName, fileSize, sha1, uploadId, blockingStub, asyncStub);
        } catch (IOException e) {
            if (!Files.exists(file)) {
                log("SKIPPED because the source was deleted before upload: " + fileName);
            } else {
                log("Could not read " + fileName + ": " + e.getMessage());
            }
        }
    }

    /** Same upload_id and SHA-1 are reused for every retry of this upload attempt. */
    private void uploadWithRetry(Path file, String fileName, long fileSize, String sha1, String uploadId,
                                 MediaUploadServiceGrpc.MediaUploadServiceBlockingStub blockingStub,
                                 MediaUploadServiceGrpc.MediaUploadServiceStub asyncStub) {
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            try {
                BeginUploadReply begin = blockingStub.withDeadlineAfter(30, TimeUnit.SECONDS)
                        .beginUpload(BeginUploadRequest.newBuilder()
                                .setUploadId(uploadId)
                                .setOriginalFilename(fileName)
                                .setSha1(sha1)
                                .setFileSize(fileSize)
                                .build());

                switch (begin.getStatus()) {
                    case ACCEPTED -> {
                        InputStream input;
                        try {
                            // Open only after queue admission. If the producer deleted the file in
                            // the meantime, cancel the reservation and do not send any bytes.
                            input = Files.newInputStream(file);
                        } catch (IOException openError) {
                            blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS)
                                    .cancelUpload(CancelUploadRequest.newBuilder().setUploadId(uploadId).build());
                            if (!Files.exists(file)) {
                                log("SKIPPED because the source was deleted before bytes were sent: " + fileName);
                                return;
                            }
                            throw new RuntimeException("Could not open " + fileName, openError);
                        }
                        try (input) {
                            UploadReply reply = streamFile(input, uploadId, begin.getStreamToken(), asyncStub);
                            log("Upload response for " + fileName + ": " + reply.getStatus()
                                    + " - " + reply.getMessage());
                            return;
                        } catch (IOException streamError) {
                            // Do not cancel here: the stream's onError handler releases a partial
                            // server session. Retrying BeginUpload with the same ID is idempotent.
                            if (!Files.exists(file)) {
                                log("Source disappeared during transfer; server will discard the incomplete stream: " + fileName);
                                return;
                            }
                            throw new RuntimeException("Could not stream " + fileName, streamError);
                        }
                    }
                    case QUEUE_FULL -> {
                        // The assignment specifies drop-on-full: report the rejection and move on.
                        log("DROPPED by consumer: queue is full for " + fileName + ". No video bytes were sent.");
                        return;
                    }
                    case ALREADY_RECEIVING -> {
                        log("Previous attempt for " + fileName + " is still being received; checking again.");
                    }
                    case ALREADY_QUEUED, ALREADY_PROCESSING, ALREADY_SAVED -> {
                        log("Retry recognized as the same upload attempt: " + fileName + " (" + begin.getStatus() + ")");
                        return;
                    }
                    case DUPLICATE_CONTENT, DUPLICATE_IN_PROGRESS -> {
                        log("Duplicate content rejected for " + fileName + ": " + begin.getMessage());
                        return;
                    }
                    case CANCELLED -> {
                        log("Upload was cancelled by consumer: " + fileName);
                        return;
                    }
                    case IDEMPOTENCY_CONFLICT, INVALID_REQUEST, FAILED -> {
                        log("Upload rejected for " + fileName + ": " + begin.getMessage());
                        return;
                    }
                    default -> {
                        log("Unexpected server response for " + fileName + ": " + begin.getStatus());
                        return;
                    }
                }
            } catch (RuntimeException e) {
                log("Network/upload attempt " + (attempt + 1) + " failed for " + fileName + ": " + e.getMessage());
            }

            if (attempt < config.maxRetries()) {
                sleepBeforeRetry();
            }
        }
        log("Gave up after " + (config.maxRetries() + 1) + " attempts: " + fileName);
    }

    private UploadReply streamFile(InputStream input, String uploadId, String streamToken,
                                   MediaUploadServiceGrpc.MediaUploadServiceStub asyncStub) throws IOException {
        CompletableFuture<UploadReply> response = new CompletableFuture<>();
        StreamObserver<UploadReply> responseObserver = new StreamObserver<>() {
            @Override public void onNext(UploadReply value) { response.complete(value); }
            @Override public void onError(Throwable error) { response.completeExceptionally(error); }
            @Override public void onCompleted() {
                if (!response.isDone()) response.completeExceptionally(
                        new IllegalStateException("Server completed without an upload response."));
            }
        };

        StreamObserver<VideoChunk> requestObserver = asyncStub.withDeadlineAfter(5, TimeUnit.MINUTES)
                .uploadVideo(responseObserver);
        byte[] buffer = new byte[config.chunkSizeBytes()];
        int chunkIndex = 0;
        try {
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                requestObserver.onNext(VideoChunk.newBuilder()
                        .setUploadId(uploadId)
                        .setStreamToken(streamToken)
                        .setChunkIndex(chunkIndex++)
                        .setData(ByteString.copyFrom(buffer, 0, bytesRead))
                        .build());
            }
            requestObserver.onCompleted();
            return response.get(6, TimeUnit.MINUTES);
        } catch (IOException | RuntimeException e) {
            requestObserver.onError(e);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requestObserver.onError(e);
            throw new IOException("Interrupted while waiting for upload result", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("The server did not confirm the upload", e);
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(config.retryDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the assignment but is unavailable", e);
        }
    }

    private static boolean isSupported(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String allowed : ALLOWED_EXTENSIONS) if (allowed.equals(extension)) return true;
        return false;
    }

    private void log(String message) {
        synchronized (System.out) {
            System.out.printf("[Producer %d] %s%n", producerNumber, message);
        }
    }
}
