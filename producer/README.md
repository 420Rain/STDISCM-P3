# Producer application

This is the independently buildable producer side of the Java media-upload exercise. It scans one folder per producer thread, calculates SHA-1, asks the consumer to reserve queue capacity, and streams files in chunks through gRPC.

## Requirements

- JDK 21
- Maven 3.9.6+
- Network access to the consumer gRPC host on TCP port `50051`

## Configure

Edit `config.properties`:

- `p`: number of producer threads. The program reads `producer-1` through `producer-p`.
- `c` and `q`: must match the consumer's configuration.
- `consumer.host`: reachable IP address or DNS name of the consumer VM. Do not use `127.0.0.1` when the consumer is on another machine.
- `producer.input.dir`: directory containing the per-producer folders.

For `p=3`, add videos to `videos/producer-1`, `videos/producer-2`, and `videos/producer-3`. Supported extensions: `.mp4`, `.m4v`, `.mov`, `.mkv`, `.avi`.

## Build and run

```bash
mvn clean package
java -jar target/producer-1.0.0.jar config.properties
```

Maven generates the Java gRPC classes from `src/main/protobuf/media_upload.proto` during the build. The first build needs internet access to download dependencies and code-generation tools.

## Networking

The producer initiates outbound TCP to the consumer's `grpc.port` (default `50051`). On separate networks, use a routable IP or private VPN overlay (for example, Tailscale/WireGuard) and allow inbound TCP `50051` at the consumer VM firewall. The example uses plaintext gRPC and is for trusted lab networks only. See the root project `README.md` for Docker, VirtualBox, NAT, and cross-network setup.
