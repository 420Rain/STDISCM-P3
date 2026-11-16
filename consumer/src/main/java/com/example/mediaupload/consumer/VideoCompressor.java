package com.example.mediaupload.consumer;

import com.example.mediaupload.config.LoadFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Uses the FFmpeg executable to re-encode accepted videos into MP4/H.264. */
public final class VideoCompressor {
    private final String ffmpegExecutable;
    private final Path temporaryDirectory;

    public VideoCompressor(LoadFile.Config config) {
        this.ffmpegExecutable = config.ffmpegPath();
        this.temporaryDirectory = config.storageDir().toAbsolutePath().normalize().resolve(".incoming");
    }

    public Path compress(UploadCoordinator.UploadEntry entry) throws IOException, InterruptedException {
        if (entry.temporaryFile == null || !Files.isRegularFile(entry.temporaryFile)) {
            throw new IOException("Temporary upload file is missing.");
        }
        Path output = temporaryDirectory.resolve(entry.videoId + ".compressed.mp4");
        Files.deleteIfExists(output);

        // H.264/AAC MP4 is broadly playable in current browsers. The original upload
        // is retained only until conversion succeeds, then the temporary file is deleted.
        ProcessBuilder command = new ProcessBuilder(
                ffmpegExecutable,
                "-hide_banner", "-loglevel", "error", "-y",
                "-i", entry.temporaryFile.toString(),
                "-map", "0:v:0", "-map", "0:a?",
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "28",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart",
                output.toString()
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process;
        try {
            process = command.start();
        } catch (IOException e) {
            throw new IOException("Could not start FFmpeg. Install FFmpeg and check ffmpeg.path in config.properties.", e);
        }

        boolean finished;
        try {
            finished = process.waitFor(2, TimeUnit.HOURS);
        } catch (InterruptedException interrupted) {
            // Do not leave an orphan FFmpeg process writing into the temp directory.
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Files.deleteIfExists(output);
            throw interrupted;
        }
        if (!finished) {
            process.destroyForcibly();
            Files.deleteIfExists(output);
            throw new IOException("FFmpeg timed out while compressing " + entry.originalName);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
            Files.deleteIfExists(output);
            throw new IOException("FFmpeg could not decode/compress this video (exit code " + process.exitValue() + ").");
        }
        return output;
    }
}
