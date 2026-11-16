package com.example.mediaupload.producer;

import com.example.mediaupload.config.LoadFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Entry point for VM 1. Each producer thread owns one separate folder. */
public final class ProducerMain {
    private ProducerMain() { }

    public static void main(String[] args) throws Exception {
        String configFile = args.length > 0 ? args[0] : "config.properties";
        LoadFile.Config config = LoadFile.loadConfig(configFile);
        System.out.printf("Producer starting: p=%d, target=%s:%d%n",
                config.p(), config.consumerHost(), config.grpcPort());

        ExecutorService producerPool = Executors.newFixedThreadPool(config.p());
        List<Future<?>> tasks = new ArrayList<>();
        for (int producerNumber = 1; producerNumber <= config.p(); producerNumber++) {
            final int number = producerNumber;
            tasks.add(producerPool.submit(new ProducerWorker(number, config)));
        }

        producerPool.shutdown();
        for (Future<?> task : tasks) {
            task.get();
        }
        System.out.println("All producer folders have been processed.");
    }
}
