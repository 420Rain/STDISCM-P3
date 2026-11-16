# Consumer application

This is the independently buildable consumer side of the Java media-upload exercise. It exposes the gRPC upload service, reserves queue capacity, verifies SHA-1, runs `c` worker threads, compresses accepted videos with FFmpeg, stores MP4 files, and serves a browser gallery.

## Requirements

- JDK 21
- Maven 3.9.6+
- FFmpeg installed and available as `ffmpeg`, or set `ffmpeg.path` in `config.properties`

## Configure

Edit `config.properties`. The required assignment values are `p`, `c`, and `q`; they should match the producer configuration. `c` is the number of processing workers. `q` is the number of admission slots reserved for receiving or waiting uploads.

Default ports: gRPC `50051`, browser gallery `8080`. The server binds to all interfaces; configure the VM firewall so only trusted lab/VPN peers can reach these ports.

## Build and run

```bash
mvn clean package
java -jar target/consumer-1.0.0.jar config.properties
```

Open `http://localhost:8080` on the consumer machine. The gallery supports hover preview (up to 10 seconds), full playback on click, cancellation of receiving/queued uploads, and deletion of stored videos. The page shows the configured `p`, `c`, and `q` values under the incoming/queued uploads heading. Polling updates preserve existing video elements, so a 3-second status refresh does not restart a hover preview.

Maven generates the Java gRPC classes from `src/main/protobuf/media_upload.proto` during the build. The first build needs internet access to download dependencies and code-generation tools. FFmpeg is required at runtime for video compression.

## Networking

Allow inbound TCP `50051` from the producer for gRPC. Allow TCP `8080` only for browsers that need the gallery. For different networks, prefer a private VPN overlay and set the producer's `consumer.host` to the consumer VM's VPN address. See the root project `README.md` for Docker, VirtualBox, NAT, and cross-network setup.

## Disconnects and restarts

When gRPC detects a producer disconnect mid-stream, the consumer removes the partial temporary file and releases its queue/hash reservation. The producer retries the upload from the beginning using the same upload ID, up to its configured retry limit. This is not a byte-offset resume.

Completed MP4 files and metadata survive a consumer restart. The in-memory queue does not: startup removes unfinished files from `data/.incoming/`, so uploads that were receiving, queued, or being processed are lost and must be sent again. A still-running producer retries temporary network errors; if it exhausts retries, run it again after the consumer is back online.

## Console labels

`[Consumer]` identifies service/coordinator-level events. `[Worker 1]`, `[Worker 2]`, etc. identify consumer processing threads and are controlled by `c`. `[Producer 1]`, `[Producer 2]`, etc. are printed by producer threads and are controlled by `p`. For `c=2`, there are two workers even when `p=3`.

> Demo security: the sample has no authentication and uses plaintext gRPC/HTTP. Do not expose it to the public Internet.
