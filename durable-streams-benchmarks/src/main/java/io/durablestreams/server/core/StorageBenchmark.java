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
 * Performance benchmark comparing blocking vs virtual-thread async file storage.

 *
 * <p>Run with: {@code java StorageBenchmark}
 *
 * <p>Benchmarks:
 * <ul>
 *   <li>Sequential writes - single-threaded append operations</li>
 *   <li>Sequential reads - single-threaded read operations</li>
 *   <li>Concurrent writes - multi-threaded append operations</li>
 *   <li>Concurrent reads - multi-threaded read operations</li>
 *   <li>Mixed workload - concurrent reads and writes</li>
 *   <li>Await latency - time to wake up waiters after data arrives</li>
 * </ul>
 */
public class StorageBenchmark {

    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 1000;
    private static final int CONCURRENT_THREADS = 10;
    private static final int DATA_SIZE = 4096; // 4KB per operation
    private static final byte[] TEST_DATA = new byte[DATA_SIZE];

    static {
        new Random(42).nextBytes(TEST_DATA);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("Storage Performance Benchmark: Blocking vs Virtual Thread Async");

        System.out.println("=".repeat(80));
        System.out.println();
        System.out.printf("Configuration:%n");
        System.out.printf("  Warmup iterations:    %d%n", WARMUP_ITERATIONS);
        System.out.printf("  Benchmark iterations: %d%n", BENCHMARK_ITERATIONS);
        System.out.printf("  Concurrent threads:   %d%n", CONCURRENT_THREADS);
        System.out.printf("  Data size per op:     %d bytes%n", DATA_SIZE);
        System.out.println();

        Path tempDir = Files.createTempDirectory("storage-benchmark");
        try {
            Path blockingDir = tempDir.resolve("blocking");
            Path nioDir = tempDir.resolve("nio");
            Path adapterDir = tempDir.resolve("adapter");

            Files.createDirectories(blockingDir);
            Files.createDirectories(nioDir);
            Files.createDirectories(adapterDir);

            BlockingFileStreamStore blockingStore = new BlockingFileStreamStore(blockingDir);
            NioFileStreamStore nioStore = new NioFileStreamStore(nioDir);

            // Adapter wrapping blocking store with virtual thread executor (Java 21+)
            ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
            AsyncStreamStore adapterStore = new BlockingToAsyncAdapter(
                    new BlockingFileStreamStore(adapterDir), virtualExecutor);

            try {
                runAllBenchmarks(blockingStore, nioStore, adapterStore);
            } finally {
                nioStore.close();
                virtualExecutor.shutdown();
            }
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private static void runAllBenchmarks(
            BlockingFileStreamStore blockingStore,
            NioFileStreamStore nioStore,
            AsyncStreamStore adapterStore) throws Exception {

        System.out.println("-".repeat(80));
        System.out.println("1. SEQUENTIAL WRITE BENCHMARK");
        System.out.println("-".repeat(80));
        benchmarkSequentialWrites(blockingStore, nioStore, adapterStore);

        System.out.println();
        System.out.println("-".repeat(80));
        System.out.println("2. SEQUENTIAL READ BENCHMARK");
        System.out.println("-".repeat(80));
        benchmarkSequentialReads(blockingStore, nioStore, adapterStore);

        System.out.println();
        System.out.println("-".repeat(80));
        System.out.println("3. CONCURRENT WRITE BENCHMARK");
        System.out.println("-".repeat(80));
        benchmarkConcurrentWrites(blockingStore, nioStore, adapterStore);

        System.out.println();
        System.out.println("-".repeat(80));
        System.out.println("4. CONCURRENT READ BENCHMARK");
        System.out.println("-".repeat(80));
        benchmarkConcurrentReads(blockingStore, nioStore, adapterStore);

        System.out.println();
        System.out.println("-".repeat(80));
        System.out.println("5. MIXED WORKLOAD BENCHMARK (70% read, 30% write)");
        System.out.println("-".repeat(80));
        benchmarkMixedWorkload(blockingStore, nioStore, adapterStore);

        System.out.println();
        System.out.println("-".repeat(80));
        System.out.println("6. AWAIT LATENCY BENCHMARK");
        System.out.println("-".repeat(80));
        benchmarkAwaitLatency(blockingStore, nioStore, adapterStore);

        System.out.println();
        System.out.println("=".repeat(80));
        System.out.println("BENCHMARK COMPLETE");
        System.out.println("=".repeat(80));
    }

    // --- Sequential Write Benchmark ---

    private static void benchmarkSequentialWrites(
            BlockingFileStreamStore blockingStore,
            NioFileStreamStore nioStore,
            AsyncStreamStore adapterStore) throws Exception {

        URI blockingUrl = URI.create("http://localhost/bench/blocking-seq-write");
        URI nioUrl = URI.create("http://localhost/bench/nio-seq-write");
        URI adapterUrl = URI.create("http://localhost/bench/adapter-seq-write");

        // Create streams
        blockingStore.create(blockingUrl, new StreamConfig("application/octet-stream", null, null), null);
        nioStore.create(nioUrl, new StreamConfig("application/octet-stream", null, null), null).get();
        adapterStore.create(adapterUrl, new StreamConfig("application/octet-stream", null, null), null).get();

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            blockingStore.append(blockingUrl, "application/octet-stream", null, new ByteArrayInputStream(TEST_DATA));
            nioStore.append(nioUrl, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone())).get();
            adapterStore.append(adapterUrl, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone())).get();
        }

        // Benchmark blocking
        long blockingTime = benchmarkSync("Blocking FileStore", () -> {
            try {
                blockingStore.append(blockingUrl, "application/octet-stream", null, new ByteArrayInputStream(TEST_DATA));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Benchmark VT async
        long nioTime = benchmarkAsyncSerial("VT Async FileStore", () ->
                nioStore.append(nioUrl, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone()))
        );

        long adapterTime = benchmarkAsyncSerial("Adapter + VirtualThreads", () ->
                adapterStore.append(adapterUrl, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone()))
        );

        printComparison(blockingTime, nioTime, adapterTime);
    }

    // --- Sequential Read Benchmark ---

    private static void benchmarkSequentialReads(
            BlockingFileStreamStore blockingStore,
            NioFileStreamStore nioStore,
            AsyncStreamStore adapterStore) throws Exception {

        URI blockingUrl = URI.create("http://localhost/bench/blocking-seq-read");
        URI nioUrl = URI.create("http://localhost/bench/nio-seq-read");
        URI adapterUrl = URI.create("http://localhost/bench/adapter-seq-read");

        // Create streams with data
        byte[] largeData = new byte[DATA_SIZE * 100];
        new Random(42).nextBytes(largeData);

        blockingStore.create(blockingUrl, new StreamConfig("application/octet-stream", null, null),
                new ByteArrayInputStream(largeData));
        nioStore.create(nioUrl, new StreamConfig("application/octet-stream", null, null),
                ByteBuffer.wrap(largeData.clone())).get();
        adapterStore.create(adapterUrl, new StreamConfig("application/octet-stream", null, null),
                ByteBuffer.wrap(largeData.clone())).get();

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            blockingStore.read(blockingUrl, Offset.beginning(), DATA_SIZE);
            nioStore.read(nioUrl, Offset.beginning(), DATA_SIZE).get();
            adapterStore.read(adapterUrl, Offset.beginning(), DATA_SIZE).get();
        }

        // Benchmark
        long blockingTime = benchmarkSync("Blocking FileStore", () -> {
            try {
                blockingStore.read(blockingUrl, Offset.beginning(), DATA_SIZE);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        long nioTime = benchmarkAsyncSerial("VT Async FileStore", () ->
                nioStore.read(nioUrl, Offset.beginning(), DATA_SIZE)
        );

        long adapterTime = benchmarkAsyncSerial("Adapter + VirtualThreads", () ->
                adapterStore.read(adapterUrl, Offset.beginning(), DATA_SIZE)
        );

        printComparison(blockingTime, nioTime, adapterTime);
    }

    // --- Concurrent Write Benchmark ---

    private static final AtomicLong URL_COUNTER = new AtomicLong();

    private static void benchmarkConcurrentWrites(
            BlockingFileStreamStore blockingStore,
            NioFileStreamStore nioStore,
            AsyncStreamStore adapterStore) throws Exception {

        // Benchmark blocking with thread pool - each iteration uses unique URL
        long blockingTime = benchmarkConcurrentSync("Blocking FileStore", () -> {
            try {
                for (int i = 0; i < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; i++) {
                    URI url = URI.create("http://localhost/bench/blocking-conc-" + URL_COUNTER.incrementAndGet());
                    blockingStore.create(url, new StreamConfig("application/octet-stream", null, null), null);
                    blockingStore.append(url, "application/octet-stream", null, new ByteArrayInputStream(TEST_DATA));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        long nioTime = benchmarkConcurrentAsync("VT Async FileStore", () -> {
            try {
                for (int i = 0; i < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; i++) {
                    URI url = URI.create("http://localhost/bench/nio-conc-" + URL_COUNTER.incrementAndGet());
                    nioStore.create(url, new StreamConfig("application/octet-stream", null, null), null).get();
                    nioStore.append(url, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone())).get();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        long adapterTime = benchmarkConcurrentAsync("Adapter + VirtualThreads", () -> {
            try {
                for (int i = 0; i < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; i++) {
                    URI url = URI.create("http://localhost/bench/adapter-conc-" + URL_COUNTER.incrementAndGet());
                    adapterStore.create(url, new StreamConfig("application/octet-stream", null, null), null).get();
                    adapterStore.append(url, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone())).get();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        printComparison(blockingTime, nioTime, adapterTime);
    }

    // --- Concurrent Read Benchmark ---

    private static void benchmarkConcurrentReads(
            BlockingFileStreamStore blockingStore,
            NioFileStreamStore nioStore,
            AsyncStreamStore adapterStore) throws Exception {

        // Prepare data
        URI blockingUrl = URI.create("http://localhost/bench/blocking-conc-read");
        URI nioUrl = URI.create("http://localhost/bench/nio-conc-read");
        URI adapterUrl = URI.create("http://localhost/bench/adapter-conc-read");

        byte[] largeData = new byte[DATA_SIZE * 100];
        new Random(42).nextBytes(largeData);

        blockingStore.create(blockingUrl, new StreamConfig("application/octet-stream", null, null),
                new ByteArrayInputStream(largeData));
        nioStore.create(nioUrl, new StreamConfig("application/octet-stream", null, null),
                ByteBuffer.wrap(largeData.clone())).get();
        adapterStore.create(adapterUrl, new StreamConfig("application/octet-stream", null, null),
                ByteBuffer.wrap(largeData.clone())).get();

        // Benchmark blocking
        long blockingTime = benchmarkConcurrentSync("Blocking FileStore", () -> {
            try {
                for (int i = 0; i < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; i++) {
                    blockingStore.read(blockingUrl, Offset.beginning(), DATA_SIZE);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Benchmark VT async
        long nioTime = benchmarkConcurrentAsync("VT Async FileStore", () -> {
            try {
                List<CompletableFuture<?>> futures = new ArrayList<>();
                for (int i = 0; i < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; i++) {
                    futures.add(nioStore.read(nioUrl, Offset.beginning(), DATA_SIZE));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Benchmark adapter
        long adapterTime = benchmarkConcurrentAsync("Adapter + VirtualThreads", () -> {
            try {
                List<CompletableFuture<?>> futures = new ArrayList<>();
                for (int i = 0; i < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; i++) {
                    futures.add(adapterStore.read(adapterUrl, Offset.beginning(), DATA_SIZE));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        printComparison(blockingTime, nioTime, adapterTime);
    }

    // --- Mixed Workload Benchmark ---

    private static void benchmarkMixedWorkload(
            BlockingFileStreamStore blockingStore,
            NioFileStreamStore nioStore,
            AsyncStreamStore adapterStore) throws Exception {

        byte[] initialData = new byte[DATA_SIZE * 10];
        new Random(42).nextBytes(initialData);

        // Use ThreadLocal random to avoid contention and ensure reproducible patterns per thread
        ThreadLocal<Random> threadRandom = ThreadLocal.withInitial(() -> new Random(42));

        // Benchmark blocking - per-thread streams to avoid file locking issues
        long blockingTime = benchmarkConcurrentSync("Blocking FileStore", () -> {
            try {
                URI url = URI.create("http://localhost/bench/blocking-mixed-" + URL_COUNTER.incrementAndGet());
                blockingStore.create(url, new StreamConfig("application/octet-stream", null, null),
                        new ByteArrayInputStream(initialData.clone()));
                Random random = threadRandom.get();
                for (int i = 0; i < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; i++) {
                    if (random.nextDouble() < 0.7) {
                        blockingStore.read(url, Offset.beginning(), DATA_SIZE);
                    } else {
                        blockingStore.append(url, "application/octet-stream", null, new ByteArrayInputStream(TEST_DATA));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Benchmark VT async - per-thread streams
        long nioTime = benchmarkConcurrentAsync("VT Async FileStore", () -> {
            try {
                URI url = URI.create("http://localhost/bench/nio-mixed-" + URL_COUNTER.incrementAndGet());
                nioStore.create(url, new StreamConfig("application/octet-stream", null, null),
                        ByteBuffer.wrap(initialData.clone())).get();
                Random random = threadRandom.get();
                for (int i = 0; i < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; i++) {
                    if (random.nextDouble() < 0.7) {
                        nioStore.read(url, Offset.beginning(), DATA_SIZE).get();
                    } else {
                        nioStore.append(url, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone())).get();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Benchmark adapter - per-thread streams
        long adapterTime = benchmarkConcurrentAsync("Adapter + VirtualThreads", () -> {
            try {
                URI url = URI.create("http://localhost/bench/adapter-mixed-" + URL_COUNTER.incrementAndGet());
                adapterStore.create(url, new StreamConfig("application/octet-stream", null, null),
                        ByteBuffer.wrap(initialData.clone())).get();
                Random random = threadRandom.get();
                for (int i = 0; i < BENCHMARK_ITERATIONS / CONCURRENT_THREADS; i++) {
                    if (random.nextDouble() < 0.7) {
                        adapterStore.read(url, Offset.beginning(), DATA_SIZE).get();
                    } else {
                        adapterStore.append(url, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone())).get();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        printComparison(blockingTime, nioTime, adapterTime);
    }

    // --- Await Latency Benchmark ---

    private static void benchmarkAwaitLatency(
            BlockingFileStreamStore blockingStore,
            NioFileStreamStore nioStore,
            AsyncStreamStore adapterStore) throws Exception {

        int iterations = 100; // Fewer iterations for latency test

        // Blocking await latency
        List<Long> blockingLatencies = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            URI url = URI.create("http://localhost/bench/await-blocking-" + i);
            blockingStore.create(url, new StreamConfig("application/octet-stream", null, null), null);

            CountDownLatch started = new CountDownLatch(1);
            AtomicLong latency = new AtomicLong();

            Thread waiter = Thread.startVirtualThread(() -> {
                try {
                    started.countDown();
                    long start = System.nanoTime();
                    blockingStore.await(url, Offset.beginning(), Duration.ofSeconds(5));
                    latency.set(System.nanoTime() - start);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            started.await();
            Thread.sleep(1); // Ensure waiter is waiting
            blockingStore.append(url, "application/octet-stream", null, new ByteArrayInputStream(TEST_DATA));
            waiter.join();
            blockingLatencies.add(latency.get());
        }

        // VT async await latency
        List<Long> nioLatencies = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            URI url = URI.create("http://localhost/bench/await-nio-" + i);
            nioStore.create(url, new StreamConfig("application/octet-stream", null, null), null).get();

            long start = System.nanoTime();
            CompletableFuture<Boolean> awaitFuture = nioStore.await(url, Offset.beginning(), Duration.ofSeconds(5));

            Thread.sleep(1); // Ensure waiter is registered
            nioStore.append(url, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone())).get();

            awaitFuture.get();
            nioLatencies.add(System.nanoTime() - start);
        }

        // Adapter await latency
        List<Long> adapterLatencies = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            URI url = URI.create("http://localhost/bench/await-adapter-" + i);
            adapterStore.create(url, new StreamConfig("application/octet-stream", null, null), null).get();

            long start = System.nanoTime();
            CompletableFuture<Boolean> awaitFuture = adapterStore.await(url, Offset.beginning(), Duration.ofSeconds(5));

            Thread.sleep(1);
            adapterStore.append(url, "application/octet-stream", null, ByteBuffer.wrap(TEST_DATA.clone())).get();

            awaitFuture.get();
            adapterLatencies.add(System.nanoTime() - start);
        }

        System.out.printf("%n  Await Wake-up Latency (microseconds):%n");
        System.out.printf("    %-30s avg: %8.2f  p50: %8.2f  p99: %8.2f%n",
                "Blocking FileStore:",
                avg(blockingLatencies) / 1000.0,
                percentile(blockingLatencies, 50) / 1000.0,
                percentile(blockingLatencies, 99) / 1000.0);
        System.out.printf("    %-30s avg: %8.2f  p50: %8.2f  p99: %8.2f%n",
                "VT Async FileStore:",
                avg(nioLatencies) / 1000.0,
                percentile(nioLatencies, 50) / 1000.0,
                percentile(nioLatencies, 99) / 1000.0);
        System.out.printf("    %-30s avg: %8.2f  p50: %8.2f  p99: %8.2f%n",
                "Adapter + VirtualThreads:",
                avg(adapterLatencies) / 1000.0,
                percentile(adapterLatencies, 50) / 1000.0,
                percentile(adapterLatencies, 99) / 1000.0);
    }

    // --- Benchmark Utilities ---

    private static long benchmarkSync(String name, Runnable operation) {
        System.out.printf("  Running %s...%n", name);
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            operation.run();
        }
        long elapsed = System.nanoTime() - start;
        double opsPerSec = BENCHMARK_ITERATIONS / (elapsed / 1_000_000_000.0);
        System.out.printf("    %s: %.2f ops/sec (%.3f ms total)%n", name, opsPerSec, elapsed / 1_000_000.0);
        return elapsed;
    }

    private static <T> long benchmarkAsyncSerial(String name, Supplier<CompletableFuture<T>> operation) throws Exception {
        System.out.printf("  Running %s...%n", name);
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            operation.get().get();
        }
        long elapsed = System.nanoTime() - start;
        double opsPerSec = BENCHMARK_ITERATIONS / (elapsed / 1_000_000_000.0);
        System.out.printf("    %s: %.2f ops/sec (%.3f ms total)%n", name, opsPerSec, elapsed / 1_000_000.0);
        return elapsed;
    }

    private static <T> long benchmarkAsync(String name, Supplier<CompletableFuture<T>> operation) throws Exception {
        System.out.printf("  Running %s...%n", name);
        long start = System.nanoTime();
        List<CompletableFuture<T>> futures = new ArrayList<>(BENCHMARK_ITERATIONS);
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            futures.add(operation.get());
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        long elapsed = System.nanoTime() - start;
        double opsPerSec = BENCHMARK_ITERATIONS / (elapsed / 1_000_000_000.0);
        System.out.printf("    %s: %.2f ops/sec (%.3f ms total)%n", name, opsPerSec, elapsed / 1_000_000.0);
        return elapsed;
    }

    private static long benchmarkConcurrentSync(String name, Runnable task) throws Exception {
        System.out.printf("  Running %s with %d threads...%n", name, CONCURRENT_THREADS);
        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory());
        try {
            long start = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_THREADS; i++) {
                futures.add(executor.submit(task));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            long elapsed = System.nanoTime() - start;
            double opsPerSec = BENCHMARK_ITERATIONS / (elapsed / 1_000_000_000.0);
            System.out.printf("    %s: %.2f ops/sec (%.3f ms total)%n", name, opsPerSec, elapsed / 1_000_000.0);
            return elapsed;
        } finally {
            executor.shutdown();
        }
    }

    private static long benchmarkConcurrentAsync(String name, Runnable task) throws Exception {
        System.out.printf("  Running %s with %d threads...%n", name, CONCURRENT_THREADS);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            long start = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_THREADS; i++) {
                futures.add(executor.submit(task));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            long elapsed = System.nanoTime() - start;
            double opsPerSec = BENCHMARK_ITERATIONS / (elapsed / 1_000_000_000.0);
            System.out.printf("    %s: %.2f ops/sec (%.3f ms total)%n", name, opsPerSec, elapsed / 1_000_000.0);
            return elapsed;
        } finally {
            executor.shutdown();
        }
    }

    private static void printComparison(long blockingTime, long nioTime, long adapterTime) {
        System.out.println();
        System.out.println("  Comparison:");
        double nioSpeedup = (double) blockingTime / nioTime;
        double adapterSpeedup = (double) blockingTime / adapterTime;
        System.out.printf("    VT Async vs Blocking:     %.2fx %s%n",
                Math.abs(nioSpeedup),
                nioSpeedup > 1 ? "faster" : "slower");
        System.out.printf("    Adapter vs Blocking: %.2fx %s%n",
                Math.abs(adapterSpeedup),
                adapterSpeedup > 1 ? "faster" : "slower");
    }

    private static double avg(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static long percentile(List<Long> values, int percentile) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static void deleteDirectory(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }
}
