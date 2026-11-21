# Java Media Upload Simulation

A classroom-scale producer/consumer media upload service. The producer and consumer are independent Java applications and communicate over gRPC. The consumer has a bounded FIFO queue, `c` worker threads, SHA-1 duplicate detection, retry/idempotency handling, FFmpeg compression, and a small browser gallery.

> **Scope:** This is a teaching/demo implementation, not a production media platform. The gRPC channel uses plaintext and there is no user authentication. Keep it on localhost, a trusted LAN, or a private VPN. Do not expose the demo ports directly to the public Internet.

## 1. Project layout

```text
media-upload-system/
├── producer/                  # Build and run on producer VM / Docker host
│   ├── src/main/java/          # Producer threads, gRPC client, LoadFile.java
│   ├── src/main/protobuf/      # gRPC contract used to generate Java stubs
│   ├── config.properties
│   ├── Dockerfile
│   ├── README.md
│   └── pom.xml
├── consumer/                  # Build and run on consumer VM / Docker host
│   ├── src/main/java/          # gRPC server, queue, workers, storage, gallery API
│   ├── src/main/protobuf/      # Same gRPC contract
│   ├── src/main/resources/web/ # Browser gallery
│   ├── config.properties
│   ├── Dockerfile
│   ├── README.md
│   └── pom.xml
├── shared/
│   ├── LoadFile.java           # Updated version of the supplied reference
│   └── media_upload.proto      # Canonical copy of the gRPC contract
├── docker/
│   ├── producer-compose.properties
│   └── consumer-compose.properties
├── docker-compose.yml
└── demo-videos/
    ├── producer-1/
    ├── producer-2/
    └── producer-3/
```

The canonical `.proto` file is in `shared/`. Identical copies are kept in both Maven projects so either codebase can be built independently. Keep those copies in sync when changing the RPC contract.

## 2. Prerequisites

- JDK 21.
- Apache Maven 3.9.6 or newer (Maven 3.10.x is suitable).
- FFmpeg installed on the **consumer** machine and available on `PATH`, or set `ffmpeg.path` to its executable.
- Docker Engine + Docker Compose plugin for the Docker option.
- VirtualBox (or equivalent VM software) for the VM option.

Maven downloads gRPC, Protocol Buffers, and the code-generation tool the first time the projects are built. Internet access is needed for that initial dependency resolution.

## 3. Configuration

Both programs read `config.properties` using `LoadFile.loadConfig(...)`. The loader follows the supplied `LoadFile.java` pattern: it reads `key=value` lines, ignores blank lines and `#` comments, rejects malformed/unknown/duplicate keys, parses integer values, and validates ranges.

The three assignment inputs are:

| Key | Meaning | Example |
|---|---|---:|
| `p` | Producer threads / separate input folders | `3` |
| `c` | Consumer worker threads | `2` |
| `q` | Maximum admitted uploads that have not yet been claimed by a worker | `4` |

Other settings:

| Key | Purpose | Default |
|---|---|---|
| `consumer.host` | Producer's destination host name/IP | `127.0.0.1` |
| `grpc.port` | gRPC upload port | `50051` |
| `http.port` | Browser gallery port | `8080` |
| `producer.input.dir` | Root containing `producer-1`, `producer-2`, ... | `videos` |
| `storage.dir` | Consumer's persistent storage root | `data` |
| `ffmpeg.path` | FFmpeg executable | `ffmpeg` |
| `chunk.size.bytes` | Size of each gRPC file chunk | `65536` |
| `max.video.mb` | Maximum size of one input video | `500` |
| `max.retries` | Network retry attempts after the first attempt | `3` |
| `retry.delay.ms` | Delay between retries | `1000` |

`p`, `c`, and `q` are required and must be positive. Ports must be in the range 1-65535 and must differ. The loader also checks chunk size, retry count, retry delay, and maximum file size. The provided producer and consumer config files should use matching `p`, `c`, and `q` values. The browser displays these three values from the consumer's `/api/config` endpoint. Make sure both config files agree; the displayed values describe the consumer configuration currently running.

### Producer folders

For `p=3`, the producer scans:

```text
videos/
├── producer-1/     # Thread 1 reads only this folder
├── producer-2/     # Thread 2 reads only this folder
└── producer-3/     # Thread 3 reads only this folder
```

The producer creates a missing folder, then logs that it is empty, provided the input directory is writable. Therefore, `p` does not strictly have to equal the number of folders already present, but it should normally match the number of intended `producer-N` folders:

- If `p` is smaller than the number of folders, folders with numbers greater than `p` are ignored.
- If `p` is larger, the producer attempts to create the missing folders. On a read-only Docker volume, that creation can fail; make sure `producer-1` through `producer-p` already exist in `demo-videos/`.
- `p` is the number of producer threads in this process. It is not the number of individual video files.

Supported extensions are `.mp4`, `.m4v`, `.mov`, `.mkv`, and `.avi` (case-insensitive). Files with other extensions are skipped and logged. FFmpeg can still reject a supported extension if the actual file/codec is corrupt or unsupported.

## 4. How an upload works

1. `ProducerMain` reads the config and creates `p` producer threads. Each thread owns one `producer-N` directory.
2. A producer checks the file extension and size, calculates the SHA-1 hash, and creates one UUID `upload_id` for that upload attempt.
3. The producer calls gRPC `BeginUpload` with the upload ID, filename, SHA-1, and file size.
4. The consumer validates the request, checks the idempotency and SHA-1 indexes, and tries to reserve one queue slot.
5. If no slot is available, the consumer returns `QUEUE_FULL` immediately. The producer logs the rejection and drops that video, as required by the exercise. The video bytes are not sent unless the server replies `ACCEPTED`.
6. After acceptance, the producer streams the video in small `VideoChunk` messages. The consumer writes the chunks to a temporary file, checks chunk order and size, then independently calculates SHA-1 to verify the producer's value.
7. A fully verified video is added to the FIFO queue. One of the `c` worker threads takes it, releasing the reserved queue slot.
8. The worker runs FFmpeg to create a compressed MP4 (H.264 video + AAC audio), then moves it into the consumer's permanent `data/videos/` folder and writes metadata.
9. The HTTP gallery refreshes its list every few seconds while preserving existing video-card elements, so polling does not restart an in-progress hover preview. Hover plays at most the first 10 seconds; clicking opens the full video player. The browser uses HTTP byte-range requests so the video controls can seek.

The producer's original file is never moved or deleted by this program. Once the consumer has received its temporary copy, deleting the producer's original cannot delete the consumer's copy.

## 5. Queueing and leaky-bucket/drop behavior

The exercise says to use a leaky bucket and drop uploads beyond the capacity. This implementation uses the simple behavior the exercise describes: a **bounded FIFO queue with drop-tail rejection**.

- `q` is the number of admission slots.
- A slot is reserved by `BeginUpload`, before the file bytes are streamed.
- The slot remains reserved while the file is being received and while its job waits in the queue.
- A worker releases the slot when it takes the job from the queue and begins processing it.
- If all `q` slots are reserved, the next `BeginUpload` receives `QUEUE_FULL`. It is rejected before the producer sends any video chunks.
- A producer does not retry `QUEUE_FULL`: that video is dropped. Network failures are retried separately using the same `upload_id`. No upload is silently accepted into an unbounded queue.
- Once a worker has taken a job, it is being processed and no longer occupies a queue slot.

This is not a rate-based textbook leaky-bucket traffic shaper. It is the requested fixed-capacity/drop-on-full queue behavior, implemented with a `Semaphore` for admission and an `ArrayBlockingQueue` for FIFO waiting. It intentionally counts in-progress receiving uploads as reserved slots, so the consumer can warn the producer early rather than after the whole file has crossed the network.

Example with `c=2` and `q=4`: up to four uploads can be receiving or waiting for a worker. When a worker takes one, a slot becomes available. At most two videos are compressed at once because there are two workers.

## 6. Duplicate detection, retries, and idempotency

- The client sends a SHA-1 hash in `BeginUpload`, allowing the consumer to reject a hash it already knows before the file is streamed.
- The consumer reserves the hash while that upload is receiving/queued/processing. A second upload ID with the same hash is rejected as `DUPLICATE_IN_PROGRESS`.
- After all chunks arrive, the consumer recalculates SHA-1 and compares it with the declared value. A mismatch is discarded.
- The same `upload_id` + same SHA-1 is a retry of one logical upload. The consumer responds with the existing state (`ALREADY_RECEIVING`, `ALREADY_QUEUED`, `ALREADY_PROCESSING`, or `ALREADY_SAVED`) rather than creating a second copy.
- The same `upload_id` with a different SHA-1 returns `IDEMPOTENCY_CONFLICT`.
- A different upload ID with the same SHA-1 is separate intent but duplicate content, so it is rejected with `DUPLICATE_CONTENT` (or `DUPLICATE_IN_PROGRESS` while the first copy is being handled).
- If a gRPC stream disconnects before finishing, the server closes and deletes the incomplete temporary file and releases its queue/hash reservation. The producer retries the network operation using the same upload ID. If the original `BeginUpload` response was lost, a repeated `BeginUpload` for a still-receiving ID rotates the stream token and restarts the temporary file from byte zero; callbacks from the old stream cannot cancel the new stream.

The SHA-1 index and saved upload metadata are persisted under `data/metadata/`, so stored-video duplicate detection and successful upload-ID idempotency survive a normal consumer restart. The in-memory queue is not durable: a consumer restart discards unfinished/queued temporary uploads. The producer can retry them, but this demo is not a crash-proof message broker. SHA-1 is used because the assignment explicitly requests it; SHA-256 is preferable for new security-sensitive systems.

### Producer disconnects halfway through a video

When gRPC detects that the producer stream has disconnected, the server's stream error callback calls `abortReceiving`. It closes and deletes that upload's incomplete `.part` file, releases the reserved queue slot and SHA-1 reservation, and logs the discarded upload. The producer's retry loop reuses the same `upload_id` and hash; once the consumer is reachable again, the consumer starts the transfer again from byte zero with a fresh stream token. This is a restart-from-the-beginning retry, not a byte-offset resume. If the network is in a half-open state, detection may not be immediate; this demo does not add a separate upload-session expiry timer.

### Consumer shuts down unexpectedly

Completed videos and their metadata under `data/videos/` and `data/metadata/` survive a normal restart. The queue itself is in memory, however, so receiving/queued/processing work is not durable. On startup, the consumer restores completed-video metadata and removes leftover files from `data/.incoming/`; unfinished uploads and queued jobs are therefore discarded. A producer that is still running will retry transient network failures up to `max.retries`; if it exhausts those attempts, start the producer program again after the consumer is available. Re-running the producer scans the source folders again. A new run generates new upload IDs, while SHA-1 still prevents already-stored identical content from being stored twice.

The consumer installs a shutdown hook for normal stops such as Ctrl+C/SIGTERM: it stops accepting requests and interrupts worker threads. A power loss, forced process kill, or machine crash cannot run that hook, so startup cleanup is the fallback. This is a simple teaching implementation, not a durable job broker.

## 7. Deletion rules

| Situation | Behavior |
|---|---|
| Producer deletes source before it is opened for upload | Producer skips it and logs the deletion. |
| Producer deletes source after the consumer has received it | Consumer's independent temporary/final copy remains. |
| Consumer deletes a queued upload in the gallery | `DELETE /api/uploads/{upload_id}` removes it from the queue, marks it cancelled, releases the queue/hash reservation, and deletes the temporary file. |
| A worker has already taken the queued job | Cancellation is rejected as too late; the UI reports that a worker has picked it up. |
| Consumer deletes a saved video | `DELETE /api/videos/{video_id}` removes the compressed file, removes the gallery entry, updates the SHA-1 index, and writes a deletion tombstone for that upload ID. |

A producer-side deletion after upload is intentionally not sent to the consumer. The producer and consumer files are separate copies.

## 8. Build and run directly with Maven

Open two terminals. Both projects can be built independently because each contains the same `.proto` contract and its own generated Java stubs.

### Build consumer

```bash
cd consumer
mvn clean package
```

### Build producer

```bash
cd producer
mvn clean package
```

The executable shaded JARs are:

```text
consumer/target/consumer-1.0.0.jar
producer/target/producer-1.0.0.jar
```

### Run consumer first

```bash
cd consumer
java -jar target/consumer-1.0.0.jar config.properties
```

It starts the gRPC server on TCP `50051`, the web gallery on TCP `8080`, and `c` worker threads. Open `http://localhost:8080` on the consumer machine.

### Run producer second

```bash
cd producer
java -jar target/producer-1.0.0.jar config.properties
```

Set `consumer.host` in the producer's config to the consumer's reachable IP/DNS name. The producer creates one thread per `producer-N` directory and exits after processing those folders.

### Create test videos

Use FFmpeg on any machine to create a small test clip:

```bash
ffmpeg -f lavfi -i testsrc=duration=12:size=640x360:rate=24 \
  -f lavfi -i sine=frequency=1000:duration=12 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac demo.mp4
```

Copy `demo.mp4` into `producer-1/`, `producer-2/`, etc. To test duplicate detection, copy the exact same file bytes into two folders (renaming the copy is okay). To test unsupported files, put a `.txt` file in one folder; it should be logged and skipped.

## 9. Run with Docker Compose

This option runs the producer and consumer containers on one Docker host. Compose creates a private network and the producer reaches the consumer using its service name, `consumer`.

1. Put sample videos under `demo-videos/producer-1`, `producer-2`, etc. (Create the video folders if needed.)
2. Review `docker/producer-compose.properties`. It uses `consumer.host=consumer`; do not replace this with `localhost` inside the producer container.
3. From the project root, build and start:

```bash
docker compose up --build
```

4. Open the gallery on the Docker host at `http://localhost:8080`.
5. The producer container exits after it finishes scanning its folders. The consumer remains running.
6. Stop the consumer with `Ctrl+C` or `docker compose down`. Uploaded MP4s and metadata remain in `consumer-data/` on the host.

To run the containers on **different Docker hosts or different networks**, Compose service-name DNS will not cross hosts. Publish TCP `50051` on the consumer Docker host (already mapped in `docker-compose.yml`), make that host reachable through a private VPN or routed network, and change `consumer.host` in the producer's mounted config to the consumer host's VPN/routable IP. Allow TCP `50051` through the consumer host firewall. Allow TCP `8080` only for browsers/users who need the gallery. The producer container must be able to route to that IP.

## 10. Run with VirtualBox (or another VM platform)

Use two VMs:

- **VM 1:** Producer application, JDK 21, Maven, and the `producer/` folder with its videos.
- **VM 2:** Consumer application, JDK 21, Maven, FFmpeg, and the `consumer/` folder.

### A. Put both VMs on the same LAN

1. Shut down both VMs.
2. In VirtualBox, open **Settings → Network → Adapter 1** and select **Bridged Adapter** for each VM. Choose the physical network adapter that reaches the LAN.
3. Start both VMs and find their LAN addresses (`ipconfig` on Windows, `ip addr` on Linux).
4. Make the consumer's address stable (DHCP reservation or static address).
5. On VM 2, allow inbound TCP `50051` for gRPC and TCP `8080` for the gallery in its OS firewall.
6. Set `consumer.host=<VM-2-LAN-IP>` in VM 1's `producer/config.properties`.
7. Start the consumer on VM 2, then the producer on VM 1.
8. Open `http://<VM-2-LAN-IP>:8080` from a browser that can reach VM 2.

### B. The VMs are on different networks (for example, separate homes/campuses)

Bridged mode alone does **not** make two private LANs routable to each other. Use one of these network arrangements:

**Recommended for a class demo: private VPN overlay (Tailscale or WireGuard).**

1. Install and authenticate both VMs (or their hosts) into the same private VPN network.
2. Confirm VM 1 can reach VM 2's VPN address. Test TCP connectivity to port `50051` before starting the Java programs.
3. Set `consumer.host` in VM 1's producer config to VM 2's VPN IP/name, not `127.0.0.1` and not the producer's own address.
4. Allow inbound TCP `50051` on VM 2's firewall from the VPN interface/peer. Allow TCP `8080` for the browser device if it also needs gallery access.
5. Keep VirtualBox networking as Bridged or NAT as appropriate for your VPN installation. The VPN client must be able to reach the guest VM itself (not just the host).

**Alternative: public IP + router port forwarding.**

1. Give VM 2 a stable private LAN IP.
2. Configure the router in front of VM 2 to forward TCP `50051` to VM 2's TCP `50051`. Forward TCP `8080` only if remote gallery access is required.
3. If VM 2 uses VirtualBox NAT, also configure VirtualBox NAT port-forwarding from the host to the guest for the same ports.
4. Set `consumer.host` in VM 1 to the public IP/DNS name of the consumer-side router.
5. Permit the forwarded connections in the router and VM firewall.

**Security warning:** The sample gRPC connection uses plaintext and the sample HTTP gallery has no authentication. Do not expose these ports to the public Internet for real use. Prefer a private VPN for this assignment. A production version should use gRPC TLS, authentication/authorization, HTTPS, upload quotas, and access controls.

## 11. Useful network checks

From the producer VM, test the consumer address before starting the producer:

Linux/macOS:

```bash
nc -vz <consumer-ip-or-vpn-name> 50051
```

Windows PowerShell:

```powershell
Test-NetConnection <consumer-ip-or-vpn-name> -Port 50051
```

A successful ping alone does not prove the TCP port is reachable. If the test fails, check the consumer bind address, VM adapter mode, host/router NAT forwarding, VPN routes, and both host and guest firewalls.

## 12. Test scenarios

| Test | How to try it | Expected result |
|---|---|---|
| Normal upload | Put a supported clip in `producer-1/` and run both programs | Video is compressed and appears in the gallery. |
| Multiple producers | Put different files in two or more producer folders | Producer threads upload concurrently. |
| Multiple consumers | Set `c=2` or higher | Up to `c` FFmpeg jobs run at the same time. |
| Queue full | Set `q=1`, use `c=1`, and upload several large clips from different producer folders | Consumer responds `QUEUE_FULL`; producer logs the drop and sends no video chunks. |
| Duplicate file | Put byte-identical files in two producer folders | One content hash is stored; the other upload is rejected as duplicate/already in progress. |
| Idempotent retry | Interrupt the network during an upload, then allow producer retries | The producer reuses its original upload ID; the server either resumes after the failed stream is cleaned or reports the existing queued/processing/saved state. |
| Same upload ID, different content | Reuse an upload ID with a different SHA-1 using a gRPC test client | Consumer returns `IDEMPOTENCY_CONFLICT`. |
| Producer deletes before upload | Remove a file before its producer thread opens it | Producer logs a skip; no consumer video is created. |
| Producer deletes after upload | Delete the original from the producer folder after the consumer reports queued/stored | Consumer's copy continues through compression and remains in the gallery. |
| Cancel while queued | Watch the active uploads list and click Cancel while status is `QUEUED` | Queue entry and temporary file are removed. If a worker already claimed it, cancellation is rejected. |
| Consumer deletes stored video | Click Delete on a gallery card | Card disappears and compressed file/metadata index are updated. |
| Unsupported type | Add `notes.txt` to a producer folder | Producer logs it as unsupported and skips it; consumer validates extension too. |
| Corrupt supported extension | Rename a non-video file to `.mp4` | FFmpeg fails; worker logs the failure and temporary file is removed. |

## 13. Synchronization mechanisms used

| Mechanism | Where | Why |
|---|---|---|
| `ExecutorService` / producer threads | Producer | Runs `p` folder readers concurrently. |
| `ArrayBlockingQueue` | Consumer | Thread-safe FIFO queue with capacity `q`. |
| `Semaphore` | Consumer admission | Reserves a slot before the producer streams bytes; returns `QUEUE_FULL` if no permits remain. |
| `synchronized (stateLock)` | Upload coordinator | Makes idempotency/hash reservation changes and queue cancellation decisions atomic. |
| `ConcurrentHashMap` | Upload/video/hash indexes | Safely stores shared lookup maps used by gRPC, workers, and the web server. |
| `synchronized (entry)` | Per-upload chunk writes | Keeps chunk order and byte counters consistent while writing a temporary file. |
| `BlockingQueue.take()` | Consumer workers | Blocks idle workers until a job is available; no busy-wait loop. |
| Atomic file move (where supported) | Storage | Avoids exposing a partially written compressed file as a completed gallery item. |

## 14. Known simplifications

- The queue itself is in memory. Saved videos and their idempotency metadata persist, but an abrupt consumer restart discards unfinished/queued temporary uploads.
- One video is uploaded per gRPC client-streaming RPC after a separate `BeginUpload` call. This keeps queue admission simple and lets the consumer notify producers before data transfer.
- Videos are transcoded to MP4/H.264/AAC using FFmpeg; compression quality is set to CRF 28 / `veryfast` for a reasonable demo trade-off. Large files can take time and use CPU.
- The web server is a small built-in Java HTTP server. It is intentionally not a full production web framework.
- SHA-1 matches the assignment specification but is not recommended for security-sensitive content identity. Use SHA-256 for a production design.
- The demo does not authenticate producer identities. Do not deploy it on an untrusted network.

## 15. Implementation versions and references

Versions are pinned in the Maven POM files so both applications generate compatible gRPC stubs:

- Java 21
- gRPC Java 1.85.1
- Protocol Buffers Java 4.36.2
- `io.github.ascopes:protobuf-maven-plugin` 5.1.12

Useful official references:

- [gRPC Java generated-code guide](https://grpc.io/docs/languages/java/generated-code/)
- [gRPC Java releases](https://github.com/grpc/grpc-java/releases)
- [Ascopes Protobuf Maven Plugin](https://github.com/ascopes/protobuf-maven-plugin)
- [Protocol Buffers releases](https://github.com/protocolbuffers/protobuf/releases)
- [Apache Maven installation](https://maven.apache.org/install.html)
- [FFmpeg documentation](https://ffmpeg.org/documentation.html)


## 16. Reading the terminal log labels

- `[Consumer]` means a coordinator/server-level event, such as admitting an upload, queueing it, or cleaning up an interrupted stream. It does not identify a worker thread.
- `[Worker 1]`, `[Worker 2]`, and so on identify the consumer's processing worker threads. Their number is controlled by `c`, not `p`. With `c=2`, only Worker 1 and Worker 2 exist. Set `c=3` if you want three consumer workers.
- `[Producer 1]`, `[Producer 2]`, and so on appear in the producer terminal and identify producer threads. Their number is controlled by `p`. With `p=3`, there are three producer threads.

Worker-originated success and failure messages now include the worker number too, rather than the ambiguous `[Worker]` label.

## 17. Two laptops connected to one Wi-Fi hotspot

The laptops do not need Internet access just to exchange the upload data. They need a working local network path between them. For a simple test, connect both laptops to the same phone hotspot or Wi-Fi router. The hotspot must allow connected clients to communicate with each other; some access points enable client isolation, which blocks that traffic.

1. Run the consumer on the consumer laptop and keep it connected to the hotspot.
2. Find the consumer laptop's hotspot/Wi-Fi IPv4 address (Windows: `ipconfig`; Linux: `ip addr`).
3. Allow inbound TCP `50051` on the consumer laptop's firewall. Also allow TCP `8080` if the producer laptop will open the gallery.
4. Set `consumer.host=<consumer-laptop-ip>` in the producer's `config.properties`. Do not use `127.0.0.1` there; that would point back to the producer laptop itself.
5. Run the consumer first, then run the producer. Test the port from the producer laptop with `Test-NetConnection <consumer-laptop-ip> -Port 50051` in Windows PowerShell or `nc -vz <consumer-laptop-ip> 50051` on Linux/macOS.
6. Open the gallery on the consumer laptop at `http://localhost:8080`, or on the producer laptop at `http://<consumer-laptop-ip>:8080` if TCP `8080` is allowed.

If the two laptops are on different networks (for example, separate phone hotspots), they cannot normally reach each other's private IP addresses directly. Use a private VPN/overlay network such as Tailscale or WireGuard, or another routed connection. A VPN generally needs an Internet connection to connect separate locations, unless you have a private network linking them already. Keep this demo's unauthenticated plaintext ports private.

## 18. HTTPS gallery with mkcert

The browser gallery can be served over trusted HTTPS on `localhost` using [mkcert](https://github.com/FiloSottile/mkcert), which creates a local certificate authority and installs it in your OS/browser trust stores. The gRPC upload channel is unchanged (still plaintext).

1. Install mkcert (once per machine):

   ```powershell
   winget install FiloSottile.mkcert      # or: choco install mkcert / scoop install mkcert / brew install mkcert
   mkcert -install                        # trusts the local CA; Windows shows a confirmation dialog
   ```

   Firefox uses its own trust store; restart it after `mkcert -install`.

2. From the project root, generate a PKCS#12 keystore that Java can load directly:

   ```powershell
   mkdir certs
   mkcert -pkcs12 -p12-file certs/localhost.p12 localhost 127.0.0.1 ::1
   ```

   mkcert's default `.p12` password is `changeit`, which matches `https.keystore.password` in the provided configs. Add any other host names or LAN IPs you will browse to (for example `192.168.1.20`) to the same command. Note that other machines only trust the certificate if mkcert's root CA is installed on them too (`mkcert -CAROOT` shows where it is). `certs/` is git-ignored because it contains a private key.

3. Run the consumer as usual:
   - **Maven:** `consumer/config.properties` points at `../certs/localhost.p12`, so start it from the `consumer/` folder.
   - **Docker:** `docker-compose.yml` mounts `./certs` into the consumer at `/app/certs`, and `docker/consumer-compose.properties` points there.

4. Open **https://localhost:8080**. The startup banner prints `Gallery: https://...` when HTTPS is active.

| Key | Purpose | Default |
|---|---|---|
| `https.keystore` | PKCS#12 keystore for the gallery. If unset, the gallery uses plain HTTP. | unset |
| `https.keystore.password` | Keystore password | `changeit` |

If `https.keystore` is set but the file does not exist, the consumer logs a warning and falls back to plain HTTP, so the project still runs before certificates are generated. A keystore that exists but cannot be opened (wrong password, corrupt file) stops startup with an error.
