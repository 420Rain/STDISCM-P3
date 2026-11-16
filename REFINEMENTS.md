# Refinements and behavior notes

## Web gallery

- Header now reads `[P3] Media Upload Service`. The left logo, `CONSUMER VM` label, and right-side live indicator were removed. The original dark navbar color is retained.
- Main content uses `#f3f3f3` with black text.
- The library/upload-pipeline labels and video/active counters were removed.
- The `p`, `c`, and `q` values are displayed under “Incoming and queued uploads” using `GET /api/config` from the running consumer configuration.
- Queue status still polls every 3 seconds. Video cards are updated by `videoId` and reused instead of rebuilding the whole gallery, so the polling cycle no longer interrupts an existing hover-preview video element.

## Console worker labels

`[Consumer]` identifies service-level/coordinator messages. Worker processing messages now consistently use `[Worker N]`. `N` is controlled by `c`, not `p`. For `p=3,c=2`, there are Producer 1–3 on the producer program and Worker 1–2 on the consumer program. Set `c=3` only if you actually want three consumer worker threads.

## Producer disconnects during upload

When gRPC detects a disconnected upload stream, the consumer closes/deletes the partial temporary file and releases the admission permit and SHA-1 reservation. The producer retries the same logical attempt using the same `upload_id` and hash, transferring the file again from byte zero using a fresh stream token. It is not a resume-from-last-chunk implementation. The client upload RPC has a five-minute deadline; a half-open network can still take time to be detected.

## Unexpected consumer shutdown

Completed MP4s and metadata are stored outside the in-memory queue and remain after restart. Receiving, queued, and processing jobs are not durable. On startup, the consumer restores completed metadata and cleans leftover files in `data/.incoming/`; unfinished/queued work must be uploaded again. The producer retries transient network errors up to `max.retries`, but if it exhausts those attempts the producer process must be run again after the consumer returns.

## Producer count and folders

`p` creates `p` producer threads. Thread `N` scans `producer-N/`. Ideally `p` matches the number of intended folders. Folders above `p` are ignored. If `p` exceeds the folders present, the application tries to create the missing folders; this fails if the input volume is read-only and those folders were not pre-created.

## Two laptops and a hotspot

The laptops do not need Internet access if both connect to the same hotspot/local Wi-Fi and the hotspot permits device-to-device traffic. Set `consumer.host` in the producer config to the consumer laptop's local IPv4 address, allow TCP `50051` in the consumer firewall, and optionally allow TCP `8080` for remote gallery viewing. Some hotspots enable client isolation; in that case use another LAN or a private VPN/routed connection.
