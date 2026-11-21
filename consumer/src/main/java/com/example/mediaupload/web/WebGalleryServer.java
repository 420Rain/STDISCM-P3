package com.example.mediaupload.web;

import com.example.mediaupload.consumer.UploadCoordinator;
import com.example.mediaupload.consumer.UploadCoordinator.StoredVideo;
import com.example.mediaupload.consumer.UploadCoordinator.UploadSnapshot;
import com.example.mediaupload.grpc.ActionReply;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Lightweight browser UI and HTTP API. gRPC is used only for producer uploads. */
public final class WebGalleryServer {
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private final UploadCoordinator coordinator;
    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final boolean https;

    /**
     * Serves plain HTTP when {@code keystore} is null. Otherwise serves HTTPS using the given
     * PKCS#12 keystore (for example one produced by {@code mkcert -pkcs12}).
     */
    public WebGalleryServer(UploadCoordinator coordinator, int port, Path keystore, String keystorePassword)
            throws IOException {
        this.coordinator = coordinator;
        InetSocketAddress address = new InetSocketAddress(port);
        if (keystore == null) {
            this.server = HttpServer.create(address, 0);
            this.https = false;
        } else {
            HttpsServer httpsServer = HttpsServer.create(address, 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(loadSslContext(keystore, keystorePassword)));
            this.server = httpsServer;
            this.https = true;
        }
        this.server.createContext("/", this::handle);
        this.httpExecutor = Executors.newFixedThreadPool(8);
        this.server.setExecutor(httpExecutor);
    }

    public void start() {
        server.start();
    }

    public boolean isHttps() {
        return https;
    }

    private static SSLContext loadSslContext(Path keystore, String password) throws IOException {
        char[] pass = password.toCharArray();
        try (InputStream input = Files.newInputStream(keystore)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(input, pass);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, pass);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not load HTTPS keystore " + keystore.toAbsolutePath()
                    + " (check https.keystore.password): " + e.getMessage(), e);
        }
    }

    public void stop() {
        server.stop(0);
        httpExecutor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                serveResource(exchange, "/web/index.html", "text/html; charset=utf-8");
            } else if ("GET".equals(method) && "/app.js".equals(path)) {
                serveResource(exchange, "/web/app.js", "text/javascript; charset=utf-8");
            } else if ("GET".equals(method) && "/style.css".equals(path)) {
                serveResource(exchange, "/web/style.css", "text/css; charset=utf-8");
            } else if ("GET".equals(method) && "/health".equals(path)) {
                sendText(exchange, 200, "OK", "text/plain; charset=utf-8");
            } else if ("GET".equals(method) && "/api/videos".equals(path)) {
                sendJson(exchange, 200, videosJson(coordinator.listVideos()));
            } else if ("GET".equals(method) && "/api/uploads".equals(path)) {
                sendJson(exchange, 200, uploadsJson(coordinator.listActiveUploads()));
            } else if ("GET".equals(method) && "/api/config".equals(path)) {
                sendJson(exchange, 200, configJson(coordinator.getConfigSummary()));
            } else if ("DELETE".equals(method) && path.startsWith("/api/videos/")) {
                String videoId = decode(path.substring("/api/videos/".length()));
                ActionReply result = coordinator.deleteVideo(videoId);
                sendJson(exchange, result.getSuccess() ? 200 : 404, actionJson(result));
            } else if ("DELETE".equals(method) && path.startsWith("/api/uploads/")) {
                String uploadId = decode(path.substring("/api/uploads/".length()));
                ActionReply result = coordinator.cancelUpload(uploadId);
                sendJson(exchange, result.getSuccess() ? 200 : 409, actionJson(result));
            } else if ("GET".equals(method) && path.startsWith("/media/")) {
                String videoId = decode(path.substring("/media/".length()));
                serveVideo(exchange, videoId);
            } else {
                sendJson(exchange, 404, "{\"message\":\"Not found\"}");
            }
        } catch (Exception e) {
            if (exchange.getResponseCode() == -1) {
                sendJson(exchange, 500, "{\"message\":" + jsonString(e.getMessage() == null ? "Server error" : e.getMessage()) + "}");
            }
        } finally {
            exchange.close();
        }
    }

    private void serveResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        try (InputStream input = WebGalleryServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                sendText(exchange, 404, "Missing UI resource: " + resource, "text/plain; charset=utf-8");
                return;
            }
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        }
    }

    /** Supports byte ranges so browser video controls can seek within an MP4. */
    private void serveVideo(HttpExchange exchange, String videoId) throws IOException {
        StoredVideo video = coordinator.findVideo(videoId);
        if (video == null) {
            sendJson(exchange, 404, "{\"message\":\"Video not found\"}");
            return;
        }
        Path file = coordinatorPath(video);
        // Deletion can happen between findVideo() and this lookup. Treat that race as 404.
        if (file == null || !Files.isRegularFile(file)) {
            sendJson(exchange, 404, "{\"message\":\"Video file is missing\"}");
            return;
        }

        long size = Files.size(file);
        long start = 0;
        long end = size - 1;
        int responseCode = 200;
        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
        if (rangeHeader != null) {
            Matcher matcher = RANGE_PATTERN.matcher(rangeHeader.trim());
            if (!matcher.matches() || matcher.group(1).isEmpty()) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                sendText(exchange, 416, "Invalid range", "text/plain; charset=utf-8");
                return;
            }
            try {
                start = Long.parseLong(matcher.group(1));
                if (!matcher.group(2).isEmpty()) end = Long.parseLong(matcher.group(2));
            } catch (NumberFormatException e) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                sendText(exchange, 416, "Invalid range", "text/plain; charset=utf-8");
                return;
            }
            if (start >= size || end < start) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                sendText(exchange, 416, "Range not satisfiable", "text/plain; charset=utf-8");
                return;
            }
            end = Math.min(end, size - 1);
            responseCode = 206;
        }

        long length = end - start + 1;
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "video/mp4");
        headers.set("Accept-Ranges", "bytes");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Disposition", "inline; filename=\"" + video.videoId() + ".mp4\"");
        if (responseCode == 206) headers.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
        exchange.sendResponseHeaders(responseCode, length);
        try (InputStream input = Files.newInputStream(file); OutputStream output = exchange.getResponseBody()) {
            input.skipNBytes(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private Path coordinatorPath(StoredVideo video) {
        // The coordinator only stores generated names, never client-supplied paths.
        return coordinator.getVideoPath(video.videoId());
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        sendText(exchange, status, body, "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static String videosJson(List<StoredVideo> videos) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < videos.size(); i++) {
            StoredVideo video = videos.get(i);
            if (i > 0) json.append(',');
            json.append("{\"videoId\":").append(jsonString(video.videoId()))
                    .append(",\"uploadId\":").append(jsonString(video.uploadId()))
                    .append(",\"originalName\":").append(jsonString(video.originalName()))
                    .append(",\"sha1\":").append(jsonString(video.sha1()))
                    .append(",\"storedAt\":").append(jsonString(video.storedAt())).append('}');
        }
        return json.append(']').toString();
    }

    private static String configJson(UploadCoordinator.ConfigSummary config) {
        return "{\"p\":" + config.producerThreads()
                + ",\"c\":" + config.consumerThreads()
                + ",\"q\":" + config.maxQueueLength() + "}";
    }

    private static String uploadsJson(List<UploadSnapshot> uploads) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < uploads.size(); i++) {
            UploadSnapshot upload = uploads.get(i);
            if (i > 0) json.append(',');
            json.append("{\"uploadId\":").append(jsonString(upload.uploadId()))
                    .append(",\"originalName\":").append(jsonString(upload.originalName()))
                    .append(",\"status\":").append(jsonString(upload.status()))
                    .append(",\"videoId\":").append(jsonString(upload.videoId())).append('}');
        }
        return json.append(']').toString();
    }

    private static String actionJson(ActionReply action) {
        return "{\"success\":" + action.getSuccess() + ",\"message\":" + jsonString(action.getMessage()) + "}";
    }

    private static String jsonString(String value) {
        if (value == null) value = "";
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
