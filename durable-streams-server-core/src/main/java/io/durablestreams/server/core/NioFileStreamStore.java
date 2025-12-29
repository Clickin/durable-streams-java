package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous file-based {@link AsyncStreamStore} using Java NIO.
 *
 * <p>This implementation uses {@link AsynchronousFileChannel} for true non-blocking I/O.
 * All operations return immediately and complete asynchronously without blocking the calling thread.
 *
 * <p>Storage layout:
 * <pre>
 * baseDir/
 *   {stream-id}/
 *     meta.json      - stream metadata (JSON)
 *     data.bin       - stream content (binary)
 * </pre>
 *
 * <p>The {@link #await} method uses a notification mechanism based on {@link CompletableFuture}
 * rather than blocking threads, enabling efficient handling of many concurrent waiting clients.
 *
 * <p>Example usage:
 * <pre>{@code
 * Path baseDir = Paths.get("/var/lib/durable-streams");
 * NioFileStreamStore store = new NioFileStreamStore(baseDir);
 *
 * store.append(url, "application/octet-stream", null, ByteBuffer.wrap(data))
 *      .thenAccept(outcome -> System.out.println("Appended: " + outcome.nextOffset()));
 * }</pre>
 *
 * @see AsyncStreamStore
 */
public final class NioFileStreamStore implements AsyncStreamStore {

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
    private static final String META_FILE = "meta.json";
    private static final String DATA_FILE = "data.bin";

    private final Path baseDir;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    private final ConcurrentHashMap<URI, StreamState> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<URI, Semaphore> appendSemaphores = new ConcurrentHashMap<>();

    /**
     * Creates a new NIO file stream store.
     *
     * @param baseDir directory to store stream data
     */
    public NioFileStreamStore(Path baseDir) {
        this(baseDir, Clock.systemUTC());
    }

    /**
     * Creates a new NIO file stream store with custom clock.
     *
     * @param baseDir directory to store stream data
     * @param clock clock for TTL calculations
     */
    public NioFileStreamStore(Path baseDir, Clock clock) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nio-stream-store-scheduler");
            t.setDaemon(true);
            return t;
        });
        ensureDirectory(baseDir);
    }

    @Override
    public CompletableFuture<CreateOutcome> create(URI url, StreamConfig config, ByteBuffer initialBody) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(config, "config");

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path streamDir = resolveStreamDir(url);
                Path metaPath = streamDir.resolve(META_FILE);
                Path dataPath = streamDir.resolve(DATA_FILE);

                Instant now = clock.instant();

                // Check if stream exists
                if (Files.exists(metaPath)) {
                    StreamMeta existingMeta = readMetaSync(metaPath);
                    if (existingMeta.isExpired(now)) {
                        // Expired - delete and recreate
                        deleteStreamDir(streamDir);
                    } else {
                        // Exists - check config match
                        if (!sameConfig(existingMeta.config(), config)) {
                            return new CreateOutcome(
                                    CreateOutcome.Status.EXISTS_CONFLICT,
                                    existingMeta.toMetadata(),
                                    existingMeta.nextOffset()
                            );
                        }
                        return new CreateOutcome(
                                CreateOutcome.Status.EXISTS_MATCH,
                                existingMeta.toMetadata(),
                                existingMeta.nextOffset()
                        );
                    }
                }

                // Create new stream
                ensureDirectory(streamDir);

                String streamId = UUID.randomUUID().toString().replace("-", "");
                Offset initialOffset = new Offset(LexiLong.encode(0));
                Instant expiresAt = resolveExpiresAt(config, now);

                // Write initial data if provided
                long dataSize = 0;
                if (initialBody != null && initialBody.hasRemaining()) {
                    dataSize = writeDataSync(dataPath, initialBody, 0);
                } else {
                    Files.createFile(dataPath);
                }

                Offset nextOffset = new Offset(LexiLong.encode(dataSize));

                StreamMeta meta = new StreamMeta(streamId, config, nextOffset, expiresAt);
                writeMetaSync(metaPath, meta);

                // Register in memory for await coordination
                streams.put(url, new StreamState(streamDir, meta));

                return new CreateOutcome(
                        CreateOutcome.Status.CREATED,
                        meta.toMetadata(),
                        nextOffset
                );
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public CompletableFuture<AppendOutcome> append(URI url, String contentType, String streamSeq, ByteBuffer body) {
        Objects.requireNonNull(url, "url");

        if (contentType == null || contentType.isBlank()) {
            return CompletableFuture.completedFuture(
                    new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, "missing Content-Type")
            );
        }
        if (body == null || !body.hasRemaining()) {
            return CompletableFuture.completedFuture(
                    new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, "empty body")
            );
        }

        Path streamDir = resolveStreamDir(url);
        Path metaPath = streamDir.resolve(META_FILE);
        Path dataPath = streamDir.resolve(DATA_FILE);

        if (!Files.exists(metaPath)) {
            return CompletableFuture.completedFuture(
                    new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream not found")
            );
        }

        CompletableFuture<AppendOutcome> result = new CompletableFuture<>();

        Semaphore appendSemaphore = appendSemaphores.computeIfAbsent(url, u -> new Semaphore(1));
        appendSemaphore.acquireUninterruptibly();
        try {
            StreamMeta meta = readMetaSync(metaPath);
            Instant now = clock.instant();

            if (meta.isExpired(now)) {
                deleteStreamDir(streamDir);
                streams.remove(url);
                appendSemaphores.remove(url);
                result.complete(new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream expired"));
                return result;
            }

            if (!normalizeContentType(meta.config().contentType()).equals(normalizeContentType(contentType))) {
                result.complete(new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "content-type mismatch"));
                return result;
            }

            StreamState state = streams.computeIfAbsent(url, u -> new StreamState(streamDir, meta));

            long currentSize = Files.size(dataPath);
            int totalBytes = body.remaining();

            AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                    dataPath,
                    StandardOpenOption.WRITE
            );

            writeWithRetry(channel, body, currentSize, 0, totalBytes, new CompletionHandler<Void, Void>() {
                @Override
                public void completed(Void v, Void attachment) {
                    try {
                        channel.close();

                        long newSize = currentSize + totalBytes;
                        Offset nextOffset = new Offset(LexiLong.encode(newSize));

                        StreamMeta newMeta = meta.withNextOffset(nextOffset);
                        writeMetaSync(metaPath, newMeta);

                        state.updateOffset(nextOffset);
                        state.notifyWaiters();

                        result.complete(new AppendOutcome(AppendOutcome.Status.APPENDED, nextOffset, null));
                    } catch (IOException e) {
                        result.completeExceptionally(e);
                    } finally {
                        appendSemaphore.release();
                    }
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                    } finally {
                        appendSemaphore.release();
                    }
                    result.completeExceptionally(exc);
                }
            });
        } catch (IOException e) {
            result.completeExceptionally(e);
        } finally {
            if (result.isDone()) {
                appendSemaphore.release();
            }
        }


        return result;
    }

    /**
     * Writes buffer to channel with retry logic to handle partial writes.
     * Continues writing until all bytes are written or an error occurs.
     */
    private void writeWithRetry(AsynchronousFileChannel channel, ByteBuffer buffer,
                                long startPosition, long bytesWrittenSoFar, int totalBytes,
                                CompletionHandler<Void, Void> finalHandler) {
        channel.write(buffer, startPosition + bytesWrittenSoFar, null,
                new CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer bytesWritten, Void attachment) {
                        long newTotal = bytesWrittenSoFar + bytesWritten;
                        if (buffer.hasRemaining()) {
                            // Partial write - continue with remaining bytes
                            writeWithRetry(channel, buffer, startPosition, newTotal, totalBytes, finalHandler);
                        } else {
                            // All bytes written
                            finalHandler.completed(null, null);
                        }
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        finalHandler.failed(exc, null);
                    }
                });
    }

    @Override
    public CompletableFuture<Boolean> delete(URI url) {
        return CompletableFuture.supplyAsync(() -> {
            Path streamDir = resolveStreamDir(url);
            if (!Files.exists(streamDir)) {
                return false;
            }
            try {
                deleteStreamDir(streamDir);
                StreamState state = streams.remove(url);
                appendSemaphores.remove(url);
                if (state != null) {
                    state.notifyWaiters(); // Wake up any waiters
                }
                return true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<StreamMetadata>> head(URI url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path streamDir = resolveStreamDir(url);
                Path metaPath = streamDir.resolve(META_FILE);

                if (!Files.exists(metaPath)) {
                    return Optional.empty();
                }

                StreamMeta meta = readMetaSync(metaPath);
                Instant now = clock.instant();

                if (meta.isExpired(now)) {
                    deleteStreamDir(streamDir);
                    streams.remove(url);
                    return Optional.empty();
                }

                return Optional.of(meta.toMetadata());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public CompletableFuture<ReadOutcome> read(URI url, Offset startOffset, int maxBytesOrMessages) {
        Path streamDir = resolveStreamDir(url);
        Path metaPath = streamDir.resolve(META_FILE);
        Path dataPath = streamDir.resolve(DATA_FILE);

        if (!Files.exists(metaPath)) {
            return CompletableFuture.completedFuture(
                    new ReadOutcome(ReadOutcome.Status.NOT_FOUND, null, null, null, false, null, null)
            );
        }

        CompletableFuture<ReadOutcome> result = new CompletableFuture<>();

        try {
            StreamMeta meta = readMetaSync(metaPath);
            Instant now = clock.instant();

            if (meta.isExpired(now)) {
                deleteStreamDir(streamDir);
                streams.remove(url);
                result.complete(new ReadOutcome(ReadOutcome.Status.GONE, null, null, null, false, null, null));
                return result;
            }

            long pos = decodeOffset(startOffset);
            if (pos < 0) {
                result.complete(new ReadOutcome(ReadOutcome.Status.BAD_REQUEST, null, null, null, false, null, null));
                return result;
            }

            long fileSize = Files.size(dataPath);
            long readPos = Math.min(pos, fileSize);
            int limit = maxBytesOrMessages <= 0 ? DEFAULT_BUFFER_SIZE : Math.min(maxBytesOrMessages, DEFAULT_BUFFER_SIZE);
            int toRead = (int) Math.min(limit, fileSize - readPos);

            if (toRead <= 0) {
                // No data to read - up to date
                Offset nextOffset = new Offset(LexiLong.encode(fileSize));
                String etag = meta.streamId() + ":" + LexiLong.encode(readPos) + ":" + nextOffset.value();
                result.complete(new ReadOutcome(
                        ReadOutcome.Status.OK,
                        new byte[0],
                        meta.config().contentType(),
                        nextOffset,
                        true,
                        etag,
                        null
                ));
                return result;
            }

            // Async read
            AsynchronousFileChannel channel = AsynchronousFileChannel.open(dataPath, StandardOpenOption.READ);
            ByteBuffer buffer = ByteBuffer.allocate(toRead);

            channel.read(buffer, readPos, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer bytesRead, Void attachment) {
                    try {
                        channel.close();

                        buffer.flip();
                        byte[] data = new byte[buffer.remaining()];
                        buffer.get(data);

                        long nextPos = readPos + bytesRead;
                        boolean upToDate = nextPos >= fileSize;
                        Offset nextOffset = new Offset(LexiLong.encode(nextPos));
                        String etag = meta.streamId() + ":" + LexiLong.encode(readPos) + ":" + nextOffset.value();

                        result.complete(new ReadOutcome(
                                ReadOutcome.Status.OK,
                                data,
                                meta.config().contentType(),
                                nextOffset,
                                upToDate,
                                etag,
                                null
                        ));
                    } catch (IOException e) {
                        result.completeExceptionally(e);
                    }
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                    }
                    result.completeExceptionally(exc);
                }
            });

        } catch (IOException e) {
            result.completeExceptionally(e);
        }

        return result;
    }

    @Override
    public CompletableFuture<Boolean> await(URI url, Offset startOffset, Duration timeout) {
        final StreamState streamState;

        StreamState existing = streams.get(url);
        if (existing == null) {
            // Try to load from disk
            Path streamDir = resolveStreamDir(url);
            Path metaPath = streamDir.resolve(META_FILE);
            if (!Files.exists(metaPath)) {
                return CompletableFuture.completedFuture(false);
            }
            try {
                StreamMeta meta = readMetaSync(metaPath);
                if (meta.isExpired(clock.instant())) {
                    return CompletableFuture.completedFuture(false);
                }
                streamState = streams.computeIfAbsent(url, u -> new StreamState(streamDir, meta));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        } else {
            streamState = existing;
        }

        long pos = decodeOffset(startOffset);
        long currentPos = decodeOffset(streamState.currentOffset());

        // Data already available
        if (pos < currentPos) {
            return CompletableFuture.completedFuture(true);
        }

        // Register waiter
        CompletableFuture<Boolean> waiter = new CompletableFuture<>();
        streamState.addWaiter(waiter);

        // Schedule timeout
        ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
            waiter.complete(false);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        // Cleanup on completion
        waiter.whenComplete((result, ex) -> {
            timeoutFuture.cancel(false);
            streamState.removeWaiter(waiter);
        });

        return waiter;
    }

    /**
     * Shuts down the store, releasing scheduler resources.
     */
    public void close() {
        scheduler.shutdown();
    }

    // --- Internal state management ---

    private static final class StreamState {
        private final Path streamDir;
        private final AtomicLong currentPosition;
        private final CopyOnWriteArrayList<CompletableFuture<Boolean>> waiters = new CopyOnWriteArrayList<>();
        StreamState(Path streamDir, StreamMeta meta) {
            this.streamDir = streamDir;
            this.currentPosition = new AtomicLong(decodeOffset(meta.nextOffset()));
        }

        Offset currentOffset() {
            return new Offset(LexiLong.encode(currentPosition.get()));
        }

        void updateOffset(Offset newOffset) {
            currentPosition.set(decodeOffset(newOffset));
        }

        void addWaiter(CompletableFuture<Boolean> waiter) {
            waiters.add(waiter);
        }

        void removeWaiter(CompletableFuture<Boolean> waiter) {
            waiters.remove(waiter);
        }

        void notifyWaiters() {
            for (CompletableFuture<Boolean> waiter : waiters) {
                waiter.complete(true);
            }
            waiters.clear();
        }
    }

    // --- Metadata handling ---

    private record StreamMeta(
            String streamId,
            StreamConfig config,
            Offset nextOffset,
            Instant expiresAt
    ) {
        boolean isExpired(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }

        StreamMeta withNextOffset(Offset newOffset) {
            return new StreamMeta(streamId, config, newOffset, expiresAt);
        }

        StreamMetadata toMetadata() {
            Long ttlRemaining = null;
            if (expiresAt != null) {
                long seconds = Duration.between(Instant.now(), expiresAt).getSeconds();
                ttlRemaining = Math.max(seconds, 0L);
            }
            return new StreamMetadata(
                    streamId,
                    config,
                    nextOffset,
                    ttlRemaining,
                    expiresAt
            );
        }
    }

    // --- File utilities ---

    private Path resolveStreamDir(URI url) {
        // Use URL hash as directory name for safe filesystem mapping
        String hash = Integer.toHexString(url.toString().hashCode());
        return baseDir.resolve(hash);
    }

    private static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteStreamDir(Path streamDir) throws IOException {
        if (Files.exists(streamDir)) {
            try (var walk = Files.walk(streamDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
        }
    }

    private StreamMeta readMetaSync(Path metaPath) throws IOException {
        for (int i = 0; i < 3; i++) {
            String json = Files.readString(metaPath, StandardCharsets.UTF_8);
            try {
                return parseMetaJson(json);
            } catch (RuntimeException e) {
                if (i == 2) {
                    throw e;
                }
                try {
                    Thread.sleep(2L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Read interrupted", interrupted);
                }
            }
        }
        throw new IOException("Failed to read metadata");
    }

    private void writeMetaSync(Path metaPath, StreamMeta meta) throws IOException {
        String json = toMetaJson(meta);
        Path tmpPath = Files.createTempFile(metaPath.getParent(), metaPath.getFileName().toString(), ".tmp");
        Files.writeString(tmpPath, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmpPath, metaPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(tmpPath, metaPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                Files.writeString(metaPath, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.deleteIfExists(tmpPath);
            }
        } catch (IOException moveFailed) {
            Files.writeString(metaPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(tmpPath);
        }
    }

    private long writeDataSync(Path dataPath, ByteBuffer data, long position) throws IOException {
        try (var channel = AsynchronousFileChannel.open(dataPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            int written = 0;
            while (data.hasRemaining()) {
                Future<Integer> future = channel.write(data, position + written);
                try {
                    written += future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Write interrupted", e);
                } catch (ExecutionException e) {
                    throw new IOException("Write failed", e.getCause());
                }
            }
            return written;
        }
    }

    // Simple JSON serialization for metadata (no external dependencies)
    private String toMetaJson(StreamMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"streamId\":\"").append(escapeJson(meta.streamId())).append("\",");
        sb.append("\"contentType\":\"").append(escapeJson(meta.config().contentType())).append("\",");
        sb.append("\"nextOffset\":\"").append(escapeJson(meta.nextOffset().value())).append("\"");
        meta.config().ttlSeconds().ifPresent(ttl -> sb.append(",\"ttlSeconds\":").append(ttl));
        if (meta.expiresAt() != null) {
            sb.append(",\"expiresAt\":\"").append(meta.expiresAt().toString()).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private StreamMeta parseMetaJson(String json) {
        // Simple JSON parsing without external dependencies
        String streamId = extractJsonString(json, "streamId");
        String contentType = extractJsonString(json, "contentType");
        String nextOffsetStr = extractJsonString(json, "nextOffset");
        Long ttlSeconds = extractJsonLong(json, "ttlSeconds");
        String expiresAtStr = extractJsonString(json, "expiresAt");

        StreamConfig config = new StreamConfig(
                contentType,
                ttlSeconds,
                expiresAtStr != null ? Instant.parse(expiresAtStr) : null
        );

        return new StreamMeta(
                streamId,
                config,
                new Offset(nextOffsetStr),
                expiresAtStr != null ? Instant.parse(expiresAtStr) : null
        );
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return unescapeJson(json.substring(start, end));
    }

    private static Long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return null;
        return Long.parseLong(json.substring(start, end));
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // --- Config utilities ---

    private static boolean sameConfig(StreamConfig a, StreamConfig b) {
        return normalizeContentType(a.contentType()).equals(normalizeContentType(b.contentType()))
                && a.ttlSeconds().equals(b.ttlSeconds())
                && a.expiresAt().equals(b.expiresAt());
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static Instant resolveExpiresAt(StreamConfig config, Instant now) {
        if (config.ttlSeconds().isPresent()) {
            return now.plusSeconds(config.ttlSeconds().get());
        }
        return config.expiresAt().orElse(null);
    }

    private static long decodeOffset(Offset off) {
        if (off == null) return 0;
        if ("-1".equals(off.value())) return 0;
        try {
            return LexiLong.decode(off.value());
        } catch (Exception e) {
            return -1;
        }
    }
}
