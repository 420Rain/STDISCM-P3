package com.example.mediaupload.consumer;

import com.example.mediaupload.config.LoadFile;
import com.example.mediaupload.web.WebGalleryServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Entry point for VM 2. Starts the gRPC upload server, c workers, and browser gallery. */
public final class ConsumerMain {
    private ConsumerMain() { }

    public static void main(String[] args) throws Exception {
        String configFile = args.length > 0 ? args[0] : "config.properties";
        LoadFile.Config config = LoadFile.loadConfig(configFile);
        UploadCoordinator coordinator = new UploadCoordinator(config);
        VideoCompressor compressor = new VideoCompressor(config);

        List<Thread> workers = new ArrayList<>();
        for (int i = 1; i <= config.c(); i++) {
            Thread worker = new Thread(new ConsumerWorker(i, coordinator, compressor), "consumer-worker-" + i);
            worker.start();
            workers.add(worker);
        }

        Server grpcServer = ServerBuilder.forPort(config.grpcPort())
                .addService(new GrpcUploadService(coordinator))
                .build()
                .start();
        WebGalleryServer gallery = new WebGalleryServer(coordinator, config.httpPort());
        gallery.start();

        System.out.println("----------------------------------------------------");
        System.out.println("Media upload consumer is running.");
        System.out.println("gRPC upload port: " + config.grpcPort());
        System.out.println("Gallery: http://localhost:" + config.httpPort());
        System.out.printf("Producer threads (p): %d | Consumer workers (c): %d | Max queue length (q): %d%n",
                config.p(), config.c(), config.q());
        System.out.println("Stored files: " + config.storageDir().toAbsolutePath());
        System.out.println("Press Ctrl+C to stop.");
        System.out.println("----------------------------------------------------");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping consumer services...");
            gallery.stop();
            grpcServer.shutdown();
            workers.forEach(Thread::interrupt);
            try {
                grpcServer.awaitTermination(5, TimeUnit.SECONDS);
                for (Thread worker : workers) worker.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        grpcServer.awaitTermination();
    }
}
