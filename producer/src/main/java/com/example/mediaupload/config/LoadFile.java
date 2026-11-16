package com.example.mediaupload.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Reads and validates the application's key=value configuration file.
 *
 * This is an expanded version of the supplied LoadFile.java reference:
 * x/threads became p (producers), y/limit became c (consumers), and q
 * was added for the maximum number of admitted-but-not-yet-processing uploads.
 */
public final class LoadFile {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "p", "c", "q", "consumer.host", "grpc.port", "http.port",
            "producer.input.dir", "storage.dir", "ffmpeg.path",
            "chunk.size.bytes", "max.video.mb", "max.retries", "retry.delay.ms"
    );

    private LoadFile() { }

    public static Config loadConfig(String fileName) throws IOException {
        Path configPath = Path.of(fileName);
        if (!Files.isRegularFile(configPath)) {
            throw new IOException("Configuration file does not exist: " + configPath.toAbsolutePath());
        }

        Map<String, String> values = new HashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(configPath)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            String[] parts = trimmed.split("=", 2);
            if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
                throw new IllegalArgumentException("Invalid config line " + lineNumber + ": " + line);
            }

            String key = parts[0].trim();
            // The three assignment inputs are case-insensitive, like x/X and y/Y in the reference.
            if (key.equalsIgnoreCase("p") || key.equalsIgnoreCase("c") || key.equalsIgnoreCase("q")) {
                key = key.toLowerCase(Locale.ROOT);
            }
            String value = parts[1].trim();
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown config option on line " + lineNumber + ": " + key);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate config option: " + key);
            }
        }

        int p = positiveInt(values, "p", true, 1, 64);
        int c = positiveInt(values, "c", true, 1, 64);
        int q = positiveInt(values, "q", true, 1, 10_000);
        String consumerHost = values.getOrDefault("consumer.host", "127.0.0.1");
        if (consumerHost.isBlank() || consumerHost.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("consumer.host must be a non-empty host name or IP address.");
        }

        int grpcPort = positiveInt(values, "grpc.port", false, 50051, 65535);
        int httpPort = positiveInt(values, "http.port", false, 8080, 65535);
        if (grpcPort == httpPort) {
            throw new IllegalArgumentException("grpc.port and http.port must be different.");
        }

        int chunkSize = positiveInt(values, "chunk.size.bytes", false, 65_536, 1_048_576);
        if (chunkSize < 1_024) {
            throw new IllegalArgumentException("chunk.size.bytes must be at least 1024.");
        }
        int maxVideoMb = positiveInt(values, "max.video.mb", false, 500, 10_000);
        int maxRetries = nonNegativeInt(values, "max.retries", 3, 20);
        long retryDelayMs = nonNegativeLong(values, "retry.delay.ms", 1_000L, 60_000L);
        if (retryDelayMs < 100) {
            throw new IllegalArgumentException("retry.delay.ms must be at least 100.");
        }

        String inputDir = values.getOrDefault("producer.input.dir", "videos");
        String storageDir = values.getOrDefault("storage.dir", "data");
        String ffmpegPath = values.getOrDefault("ffmpeg.path", "ffmpeg");
        if (inputDir.isBlank() || storageDir.isBlank() || ffmpegPath.isBlank()) {
            throw new IllegalArgumentException("Directory and FFmpeg settings cannot be blank.");
        }

        long maxVideoBytes = Math.multiplyExact((long) maxVideoMb, 1024L * 1024L);
        return new Config(p, c, q, consumerHost, grpcPort, httpPort,
                Path.of(inputDir), Path.of(storageDir), ffmpegPath,
                chunkSize, maxVideoBytes, maxRetries, retryDelayMs);
    }

    private static int positiveInt(Map<String, String> values, String key,
                                   boolean required, int defaultValue, int maximum) {
        String raw = values.get(key);
        if (raw == null) {
            if (required) throw new IllegalArgumentException("config file must contain '" + key + "'.");
            return defaultValue;
        }
        long parsed = parseLong(key, raw);
        if (parsed < 1 || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be between 1 and " + maximum + ".");
        }
        return Math.toIntExact(parsed);
    }

    private static int nonNegativeInt(Map<String, String> values, String key, int defaultValue, int maximum) {
        String raw = values.get(key);
        if (raw == null) return defaultValue;
        long parsed = parseLong(key, raw);
        if (parsed < 0 || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be between 0 and " + maximum + ".");
        }
        return Math.toIntExact(parsed);
    }

    private static long nonNegativeLong(Map<String, String> values, String key, long defaultValue, long maximum) {
        String raw = values.get(key);
        if (raw == null) return defaultValue;
        long parsed = parseLong(key, raw);
        if (parsed < 0 || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be between 0 and " + maximum + ".");
        }
        return parsed;
    }

    private static long parseLong(String key, String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Value for '" + key + "' must be an integer.", e);
        }
    }

    /** One shared configuration shape is used by both standalone applications. */
    public record Config(
            int p,
            int c,
            int q,
            String consumerHost,
            int grpcPort,
            int httpPort,
            Path producerInputDir,
            Path storageDir,
            String ffmpegPath,
            int chunkSizeBytes,
            long maxVideoBytes,
            int maxRetries,
            long retryDelayMs) { }
}
