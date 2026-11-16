package com.example.mediaupload.consumer;

import java.nio.file.Files;
import java.nio.file.Path;

/** A consumer worker removes one queued job at a time and compresses it. */
public final class ConsumerWorker implements Runnable {
    private final int workerNumber;
    private final UploadCoordinator coordinator;
    private final VideoCompressor compressor;

    public ConsumerWorker(int workerNumber, UploadCoordinator coordinator, VideoCompressor compressor) {
        this.workerNumber = workerNumber;
        this.coordinator = coordinator;
        this.compressor = compressor;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            UploadCoordinator.UploadEntry entry = null;
            Path compressed = null;
            try {
                entry = coordinator.takeNextJob();
                if (entry == null) continue;
                System.out.printf("[Worker %d] PROCESSING %s%n", workerNumber, entry.originalName);
                compressed = compressor.compress(entry);
                coordinator.markStored(entry, compressed, workerNumber);
                compressed = null; // The file was moved into permanent storage.
                if (entry.temporaryFile != null) Files.deleteIfExists(entry.temporaryFile);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (compressed != null) {
                    try { Files.deleteIfExists(compressed); } catch (Exception ignored) { }
                }
                if (entry != null) coordinator.markFailed(entry, e.getMessage(), workerNumber);
            }
        }
        System.out.printf("[Worker %d] STOPPED.%n", workerNumber);
    }
}
