package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Blocking file-based {@link StreamStore} using traditional Java I/O.
 *
 * <p>This implementation uses {@link FileInputStream}/{@link FileOutputStream} and
 * {@link RandomAccessFile} for file operations. All operations are synchronous and
 * block the calling thread until I/O completes.
 *
 * <p>This class is provided primarily for benchmarking against {@link NioFileStreamStore}
 * to demonstrate the performance characteristics of blocking vs non-blocking I/O.
 *
 * <p>Storage layout:
 * <pre>
 * baseDir/
 *   {stream-id}/
 *     meta.json      - stream metadata (JSON)
 *     data.bin       - stream content (binary)
 * </pre>
 *
 * @see NioFileStreamStore
 * @see StreamStore
 */
public final class BlockingFileStreamStore implements StreamStore {

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
    private static final String META_FILE = "meta.json";
    private static final String DATA_FILE = "data.bin";

    private final Path baseDir;
    private final Clock clock;

    // Per-stream state for coordinating waiters
    private final ConcurrentHashMap<URI, StreamState> streams = new ConcurrentHashMap<>();

    public BlockingFileStreamStore(Path baseDir) {
        this(baseDir, Clock.systemUTC());
    }

    public BlockingFileStreamStore(Path baseDir, Clock clock) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.clock = Objects.requireNonNull(clock, "clock");
        ensureDirectory(baseDir);
    }

    @Override
    public CreateOutcome create(URI url, StreamConfig config, InputStream initialBody) throws Exception {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(config, "config");

        Path streamDir = resolveStreamDir(url);
        Path metaPath = streamDir.resolve(META_FILE);
        Path dataPath = streamDir.resolve(DATA_FILE);

        Instant now = clock.instant();

        // Check if stream exists
        if (Files.exists(metaPath)) {
            StreamMeta existingMeta = readMeta(metaPath);
            if (existingMeta.isExpired(now)) {
                deleteStreamDir(streamDir);
            } else {
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
        Instant expiresAt = resolveExpiresAt(config, now);

        // Write initial data if provided
        long dataSize = 0;
        if (initialBody != null) {
            dataSize = writeData(dataPath, initialBody);
        } else {
            Files.createFile(dataPath);
        }

        Offset nextOffset = new Offset(LexiLong.encode(dataSize));

        StreamMeta meta = new StreamMeta(streamId, config, nextOffset, expiresAt);
        writeMeta(metaPath, meta);

        // Register in memory for await coordination
        streams.put(url, new StreamState(nextOffset));

        return new CreateOutcome(
                CreateOutcome.Status.CREATED,
                meta.toMetadata(),
                nextOffset
        );
    }

    @Override
    public AppendOutcome append(URI url, String contentType, String streamSeq, InputStream body) throws Exception {
        if (contentType == null || contentType.isBlank()) {
            return new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, "missing Content-Type");
        }
        if (body == null) {
            return new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, "empty body");
        }

        Path streamDir = resolveStreamDir(url);
        Path metaPath = streamDir.resolve(META_FILE);
        Path dataPath = streamDir.resolve(DATA_FILE);

        if (!Files.exists(metaPath)) {
            return new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream not found");
        }

        StreamMeta meta = readMeta(metaPath);
        Instant now = clock.instant();

        if (meta.isExpired(now)) {
            deleteStreamDir(streamDir);
            streams.remove(url);
            return new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream expired");
        }

        if (!normalizeContentType(meta.config().contentType()).equals(normalizeContentType(contentType))) {
            return new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "content-type mismatch");
        }

        // Append data using RandomAccessFile
        long newSize;
        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "rw")) {
            raf.seek(raf.length());
            byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) != -1) {
                raf.write(buffer, 0, read);
            }
            newSize = raf.length();
        }

        Offset nextOffset = new Offset(LexiLong.encode(newSize));

        // Update metadata
        StreamMeta newMeta = meta.withNextOffset(nextOffset);
        writeMeta(metaPath, newMeta);

        // Notify waiters
        StreamState state = streams.get(url);
        if (state != null) {
            state.updateAndNotify(nextOffset);
        }

        return new AppendOutcome(AppendOutcome.Status.APPENDED, nextOffset, null);
    }

    @Override
    public boolean delete(URI url) throws Exception {
        Path streamDir = resolveStreamDir(url);
        if (!Files.exists(streamDir)) {
            return false;
        }
        deleteStreamDir(streamDir);
        StreamState state = streams.remove(url);
        if (state != null) {
            state.notifyAll_();
        }
        return true;
    }

    @Override
    public Optional<StreamMetadata> head(URI url) throws Exception {
        Path streamDir = resolveStreamDir(url);
        Path metaPath = streamDir.resolve(META_FILE);

        if (!Files.exists(metaPath)) {
            return Optional.empty();
        }

        StreamMeta meta = readMeta(metaPath);
        Instant now = clock.instant();

        if (meta.isExpired(now)) {
            deleteStreamDir(streamDir);
            streams.remove(url);
            return Optional.empty();
        }

        return Optional.of(meta.toMetadata());
    }

    @Override
    public ReadOutcome read(URI url, Offset startOffset, int maxBytesOrMessages) throws Exception {
        Path streamDir = resolveStreamDir(url);
        Path metaPath = streamDir.resolve(META_FILE);
        Path dataPath = streamDir.resolve(DATA_FILE);

        if (!Files.exists(metaPath)) {
            return new ReadOutcome(ReadOutcome.Status.NOT_FOUND, null, null, null, false, null, null);
        }

        StreamMeta meta = readMeta(metaPath);
        Instant now = clock.instant();

        if (meta.isExpired(now)) {
            deleteStreamDir(streamDir);
            streams.remove(url);
            return new ReadOutcome(ReadOutcome.Status.GONE, null, null, null, false, null, null);
        }

        long pos = decodeOffset(startOffset);
        if (pos < 0) {
            return new ReadOutcome(ReadOutcome.Status.BAD_REQUEST, null, null, null, false, null, null);
        }

        long fileSize = Files.size(dataPath);
        long readPos = Math.min(pos, fileSize);
        int limit = maxBytesOrMessages <= 0 ? DEFAULT_BUFFER_SIZE : Math.min(maxBytesOrMessages, DEFAULT_BUFFER_SIZE);
        int toRead = (int) Math.min(limit, fileSize - readPos);

        if (toRead <= 0) {
            Offset nextOffset = new Offset(LexiLong.encode(fileSize));
            String etag = meta.streamId() + ":" + LexiLong.encode(readPos) + ":" + nextOffset.value();
            return new ReadOutcome(
                    ReadOutcome.Status.OK,
                    new byte[0],
                    meta.config().contentType(),
                    nextOffset,
                    true,
                    etag,
                    null
            );
        }

        // Read using RandomAccessFile
        byte[] data = new byte[toRead];
        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "r")) {
            raf.seek(readPos);
            int bytesRead = raf.read(data);
            if (bytesRead < toRead) {
                data = Arrays.copyOf(data, bytesRead);
            }
        }

        long nextPos = readPos + data.length;
        boolean upToDate = nextPos >= fileSize;
        Offset nextOffset = new Offset(LexiLong.encode(nextPos));
        String etag = meta.streamId() + ":" + LexiLong.encode(readPos) + ":" + nextOffset.value();

        return new ReadOutcome(
                ReadOutcome.Status.OK,
                data,
                meta.config().contentType(),
                nextOffset,
                upToDate,
                etag,
                null
        );
    }

    @Override
    public boolean await(URI url, Offset startOffset, Duration timeout) throws Exception {
        StreamState state = streams.get(url);
        if (state == null) {
            Path streamDir = resolveStreamDir(url);
            Path metaPath = streamDir.resolve(META_FILE);
            if (!Files.exists(metaPath)) {
                return false;
            }
            StreamMeta meta = readMeta(metaPath);
            if (meta.isExpired(clock.instant())) {
                return false;
            }
            state = streams.computeIfAbsent(url, u -> new StreamState(meta.nextOffset()));
        }

        long pos = decodeOffset(startOffset);
        return state.awaitData(pos, timeout);
    }

    // --- Internal state for await coordination ---

    private static final class StreamState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition dataArrived = lock.newCondition();
        private volatile long currentPosition;

        StreamState(Offset initialOffset) {
            this.currentPosition = decodeOffset(initialOffset);
        }

        void updateAndNotify(Offset newOffset) {
            lock.lock();
            try {
                currentPosition = decodeOffset(newOffset);
                dataArrived.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void notifyAll_() {
            lock.lock();
            try {
                dataArrived.signalAll();
            } finally {
                lock.unlock();
            }
        }

        boolean awaitData(long requiredPosition, Duration timeout) throws InterruptedException {
            if (requiredPosition < currentPosition) {
                return true;
            }

            lock.lock();
            try {
                long nanos = timeout.toNanos();
                while (nanos > 0 && requiredPosition >= currentPosition) {
                    nanos = dataArrived.awaitNanos(nanos);
                }
                return requiredPosition < currentPosition;
            } finally {
                lock.unlock();
            }
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
            return new StreamMetadata(streamId, config, nextOffset, ttlRemaining, expiresAt);
        }
    }

    // --- File utilities ---

    private Path resolveStreamDir(URI url) {
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

    private long writeData(Path dataPath, InputStream body) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dataPath.toFile()))) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            return total;
        }
    }

    private StreamMeta readMeta(Path metaPath) throws IOException {
        String json = Files.readString(metaPath, StandardCharsets.UTF_8);
        return parseMetaJson(json);
    }

    private void writeMeta(Path metaPath, StreamMeta meta) throws IOException {
        String json = toMetaJson(meta);
        Files.writeString(metaPath, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

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
