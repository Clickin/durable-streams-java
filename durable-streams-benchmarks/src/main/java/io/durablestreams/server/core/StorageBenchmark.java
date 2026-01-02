package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Benchmark for the Optimized BlockingFileStreamStore.
 */
public class StorageBenchmark {

    private static final int BENCHMARK_ITERATIONS = 5000;
    private static final int CONCURRENT_THREADS = 50;
    private static final int DATA_SIZE = 4096;
    
    private static final byte[] TEST_DATA = new byte[DATA_SIZE];
    static { new Random(42).nextBytes(TEST_DATA); }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("Optimized Storage Performance Benchmark");
        System.out.println("=".repeat(80));

        Path tempDir = Files.createTempDirectory("storage-benchmark");
        try {
            BlockingFileStreamStore store = new BlockingFileStreamStore(tempDir.resolve("data"));

            runBenchmark(store);

            store.close(); // Not strictly implemented but good practice if it were AutoCloseable
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void runBenchmark(BlockingFileStreamStore store) throws Exception {
        System.out.println("1. SEQUENTIAL WRITE");
        benchmarkSequentialWrite(store);

        System.out.println("\n2. SEQUENTIAL READ");
        benchmarkSequentialRead(store);

        System.out.println("\n3. CONCURRENT WRITE (" + CONCURRENT_THREADS + " threads)");
        benchmarkConcurrentWrite(store);

        System.out.println("\n4. CONCURRENT READ (" + CONCURRENT_THREADS + " threads)");
        benchmarkConcurrentRead(store);
    }

    private static void benchmarkSequentialWrite(BlockingFileStreamStore store) throws Exception {
        URI url = URI.create("http://localhost/seq-w");
        store.create(url, new StreamConfig("application/octet-stream", null, null), null);
        
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            store.append(url, "application/octet-stream", null, new ByteArrayInputStream(TEST_DATA));
        }
        long duration = System.nanoTime() - start;
        printResult("Sequential Write", duration, BENCHMARK_ITERATIONS);
    }

    private static void benchmarkSequentialRead(BlockingFileStreamStore store) throws Exception {
        URI url = URI.create("http://localhost/seq-r");
        store.create(url, new StreamConfig("application/octet-stream", null, null), new ByteArrayInputStream(TEST_DATA));
        
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            store.read(url, Offset.beginning(), DATA_SIZE);
        }
        long duration = System.nanoTime() - start;
        printResult("Sequential Read", duration, BENCHMARK_ITERATIONS);
    }

    private static void benchmarkConcurrentWrite(BlockingFileStreamStore store) throws Exception {
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        AtomicLong counter = new AtomicLong();
        
        long start = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            fs.add(ex.submit(() -> {
                try {
                    for (int j = 0; j < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; j++) {
                        URI url = URI.create("http://localhost/con-w-" + counter.incrementAndGet());
                        store.create(url, new StreamConfig("application/octet-stream", null, null), null);
                        store.append(url, "application/octet-stream", null, new ByteArrayInputStream(TEST_DATA));
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }));
        }
        for (Future<?> f : fs) f.get();
        long duration = System.nanoTime() - start;
        printResult("Concurrent Write", duration, BENCHMARK_ITERATIONS);
        ex.shutdown();
        ex.close();
    }

    private static void benchmarkConcurrentRead(BlockingFileStreamStore store) throws Exception {
        URI url = URI.create("http://localhost/con-r");
        store.create(url, new StreamConfig("application/octet-stream", null, null), new ByteArrayInputStream(TEST_DATA));
        
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        long start = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            fs.add(ex.submit(() -> {
                try {
                    for (int j = 0; j < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; j++) {
                        store.read(url, Offset.beginning(), DATA_SIZE);
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }));
        }
        for (Future<?> f : fs) f.get();
        long duration = System.nanoTime() - start;
        printResult("Concurrent Read", duration, BENCHMARK_ITERATIONS);
        ex.shutdown();
        ex.close();
    }

    private static void printResult(String name, long nanos, int iterations) {
        double seconds = nanos / 1_000_000_000.0;
        System.out.printf("  %-20s: %.2f ops/sec (%.3f ms total)%n", name, iterations / seconds, nanos / 1_000_000.0);
    }

    private static void deleteDirectory(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (Exception e) {}
                });
            }
        }
    }
}
