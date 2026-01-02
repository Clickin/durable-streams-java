package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.json.spi.JsonCodec;
import io.durablestreams.json.spi.JsonException;
import io.durablestreams.server.core.metadata.FileStreamMetadata;
import io.durablestreams.server.core.metadata.MetadataStore;
import io.durablestreams.server.core.metadata.RocksDbMetadataStore;
import io.durablestreams.server.spi.*;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public final class BlockingFileStreamStore implements StreamStore, AutoCloseable {

    private static final String DATA_FILE = "data.bin";
    private static final byte[] EMPTY_JSON_ARRAY = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] NEWLINE = new byte[] { '\n' };

    private final Path baseDir;
    private final Clock clock;
    private final MetadataStore metadataStore;
    private final StreamCodecRegistry codecs;
    private final Optional<JsonCodec> jsonCodec;

    private final ConcurrentHashMap<URI, ReentrantLock> writeLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<URI, ConcurrentLinkedQueue<CompletableFuture<Boolean>>> awaiters = new ConcurrentHashMap<>();

    public BlockingFileStreamStore(Path baseDir) {
        this(baseDir, Clock.systemUTC(), null, ServiceLoaderCodecRegistry.defaultRegistry());
    }

    public BlockingFileStreamStore(Path baseDir, Clock clock) {
        this(baseDir, clock, null, ServiceLoaderCodecRegistry.defaultRegistry());
    }

    public BlockingFileStreamStore(Path baseDir, MetadataStore metadataStore) {
        this(baseDir, Clock.systemUTC(), metadataStore, ServiceLoaderCodecRegistry.defaultRegistry());
    }

    public BlockingFileStreamStore(Path baseDir, Clock clock, MetadataStore metadataStore, StreamCodecRegistry codecs) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metadataStore = metadataStore != null ? metadataStore : new RocksDbMetadataStore(baseDir.resolve("metadata"));
        this.codecs = Objects.requireNonNull(codecs, "codecs");
        this.jsonCodec = loadJsonCodec();

        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public CreateOutcome create(URI url, StreamConfig config, InputStream initialBody) throws Exception {
        Path streamDir = resolveStreamDir(url);
        Path dataPath = streamDir.resolve(DATA_FILE);
        Instant now = clock.instant();

        ReentrantLock lock = writeLocks.computeIfAbsent(url, k -> new ReentrantLock());
        lock.lock();
        try {
            Optional<FileStreamMetadata> existing = metadataStore.get(url);
            if (existing.isPresent()) {
                FileStreamMetadata meta = existing.get();
                if (meta.isExpired(now)) {
                    deleteStreamSync(url, streamDir);
                } else {
                    if (!sameConfig(meta.config(), config)) {
                        return new CreateOutcome(CreateOutcome.Status.EXISTS_CONFLICT, toMetadata(meta, now), meta.nextOffset());
                    }
                    return new CreateOutcome(CreateOutcome.Status.EXISTS_MATCH, toMetadata(meta, now), meta.nextOffset());
                }
            }

            Files.createDirectories(streamDir);

            long dataSize = 0;
            if (initialBody != null) {
                try (FileChannel channel = FileChannel.open(dataPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    if (isJson(config.contentType())) {
                        dataSize = appendJson(channel, initialBody);
                    } else {
                        dataSize = writeBinary(channel, initialBody);
                    }
                }
            } else {
                Files.createFile(dataPath);
            }

            Offset nextOffset = new Offset(LexiLong.encode(dataSize));
            Instant expiresAt = resolveExpiresAt(config, now);
            FileStreamMetadata meta = new FileStreamMetadata(generateStreamId(), config, nextOffset, expiresAt);
            metadataStore.put(url, meta);

            return new CreateOutcome(CreateOutcome.Status.CREATED, toMetadata(meta, now), nextOffset);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AppendOutcome append(URI url, String contentType, String streamSeq, InputStream body) throws Exception {
        ReentrantLock lock = writeLocks.computeIfAbsent(url, k -> new ReentrantLock());
        lock.lock();
        try {
            Optional<FileStreamMetadata> metaOpt = metadataStore.get(url);
            if (metaOpt.isEmpty()) return new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "Stream not found");

            FileStreamMetadata meta = metaOpt.get();
            if (meta.isExpired(clock.instant())) {
                deleteStreamSync(url, resolveStreamDir(url));
                return new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "Stream expired");
            }

            if (!normalizeContentType(meta.config().contentType()).equals(normalizeContentType(contentType))) {
                return new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "Content-Type mismatch");
            }

            // Sequence check
            if (streamSeq != null && meta.lastSeq() != null && streamSeq.compareTo(meta.lastSeq()) <= 0) {
                return new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "Stream-Seq regression");
            }

            Path dataPath = resolveStreamDir(url).resolve(DATA_FILE);
            long bytesWritten;
            long currentSize;

            try (FileChannel channel = FileChannel.open(dataPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                currentSize = channel.position();
                if (isJson(contentType)) {
                    bytesWritten = appendJson(channel, body);
                    if (bytesWritten == 0) {
                         return new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, "empty body");
                    }
                } else {
                    bytesWritten = writeBinary(channel, body);
                }
            }

            Offset newOffset = new Offset(LexiLong.encode(currentSize + bytesWritten));
            FileStreamMetadata newMeta = meta.withNextOffsetAndSeq(newOffset, streamSeq);
            metadataStore.put(url, newMeta);

            notifyWaiters(url);

            return new AppendOutcome(AppendOutcome.Status.APPENDED, newOffset, null);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(URI url) throws Exception {
        ReentrantLock lock = writeLocks.get(url);
        if (lock != null) {
            lock.lock();
            try {
                boolean removed = metadataStore.delete(url);
                deleteStreamSync(url, resolveStreamDir(url));
                return removed;
            } finally {
                lock.unlock();
            }
        } else {
            boolean removed = metadataStore.delete(url);
            deleteStreamSync(url, resolveStreamDir(url));
            return removed;
        }
    }

    @Override
    public Optional<StreamMetadata> head(URI url) throws Exception {
        Optional<FileStreamMetadata> meta = metadataStore.get(url);
        if (meta.isPresent() && meta.get().isExpired(clock.instant())) {
            delete(url);
            return Optional.empty();
        }
        return meta.map(m -> toMetadata(m, clock.instant()));
    }

    @Override
    public ReadOutcome read(URI url, Offset startOffset, int limit) throws Exception {
        Optional<FileStreamMetadata> metaOpt = metadataStore.get(url);
        if (metaOpt.isEmpty() || metaOpt.get().isExpired(clock.instant())) {
            return new ReadOutcome(ReadOutcome.Status.NOT_FOUND, null, null, null, false, null, null);
        }
        FileStreamMetadata meta = metaOpt.get();
        Path dataPath = resolveStreamDir(url).resolve(DATA_FILE);

        if (!Files.exists(dataPath)) {
            byte[] body = isJson(meta.config().contentType()) ? EMPTY_JSON_ARRAY : new byte[0];
            return new ReadOutcome(ReadOutcome.Status.OK, body, meta.config().contentType(), startOffset, true, null, null);
        }

        long startPos = decodeOffsetStrict(startOffset);
        long fileSize = Files.size(dataPath);

        if (startPos >= fileSize) {
            byte[] body = isJson(meta.config().contentType()) ? EMPTY_JSON_ARRAY : new byte[0];
            Offset tail = new Offset(LexiLong.encode(fileSize));
            String etag = generateEtag(meta, startPos, fileSize);
            return new ReadOutcome(ReadOutcome.Status.OK, body, meta.config().contentType(), tail, true, etag, null);
        }

        if (isJson(meta.config().contentType())) {
            return readJson(dataPath, meta, startPos, limit, fileSize);
        } else {
            return readBinary(dataPath, meta, startPos, limit, fileSize);
        }
    }

    private String generateEtag(FileStreamMetadata meta, long start, long end) {
        return meta.streamId() + ":" + LexiLong.encode(start) + ":" + LexiLong.encode(end);
    }

    private ReadOutcome readBinary(Path path, FileStreamMetadata meta, long start, int limit, long fileSize) {
        int effectiveLimit = limit <= 0 ? 64 * 1024 : Math.min(limit, 1024 * 1024);
        int toRead = (int) Math.min(effectiveLimit, fileSize - start);

        ReadOutcome.FileRegion region = new ReadOutcome.FileRegion(path, start, toRead);
        long nextPos = start + toRead;
        boolean upToDate = nextPos >= fileSize;
        Offset nextOffset = new Offset(LexiLong.encode(nextPos));
        String etag = generateEtag(meta, start, nextPos);

        return new ReadOutcome(ReadOutcome.Status.OK, null, meta.config().contentType(), nextOffset, upToDate, etag, null, region);
    }

    private ReadOutcome readJson(Path path, FileStreamMetadata meta, long start, int maxMessages, long fileSize) throws IOException {
        int limit = maxMessages <= 0 ? 100 : maxMessages;

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            ByteArrayOutputStream bodyBuilder = new ByteArrayOutputStream();
            bodyBuilder.write('[');

            int messagesFound = 0;
            long currentPos = start;
            boolean first = true;

            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
            while (currentPos < fileSize && messagesFound < limit) {
                buffer.clear();
                int read = channel.read(buffer);
                if (read == -1) break;
                buffer.flip();

                while (buffer.hasRemaining() && messagesFound < limit) {
                    byte b = buffer.get();
                    currentPos++;
                    if (b == '\n') {
                        if (!first) bodyBuilder.write(',');
                        bodyBuilder.write(lineBuf.toByteArray());
                        lineBuf.reset();
                        messagesFound++;
                        first = false;
                    } else {
                        lineBuf.write(b);
                    }
                }
            }

            bodyBuilder.write(']');
            Offset nextOffset = new Offset(LexiLong.encode(currentPos));
            String etag = generateEtag(meta, start, currentPos);

            return new ReadOutcome(
                    ReadOutcome.Status.OK,
                    bodyBuilder.toByteArray(),
                    meta.config().contentType(),
                    nextOffset,
                    currentPos >= fileSize,
                    etag,
                    null
            );
        }
    }

    private long appendJson(FileChannel channel, InputStream body) throws IOException {
        byte[] raw = body.readAllBytes();
        if (raw.length == 0) return 0;

        JsonCodec codec = requireJsonCodec();
        try {
            if (codec.isJsonArray(raw)) {
                List<Object> values = codec.readList(raw, Object.class);
                if (values.isEmpty()) return 0;
                long total = 0;
                for (Object value : values) {
                    total += writeJsonValue(channel, codec, value);
                }
                return total;
            }

            Object value = codec.readValue(raw, Object.class);
            return writeJsonValue(channel, codec, value);
        } catch (JsonException e) {
            throw new IllegalArgumentException("invalid json", e);
        }
    }

    private long writeJsonValue(FileChannel channel, JsonCodec codec, Object value) throws IOException, JsonException {
        byte[] bytes = codec.writeBytes(value);
        long written = channel.write(ByteBuffer.wrap(bytes));
        written += channel.write(ByteBuffer.wrap(NEWLINE));
        return written;
    }

    private long writeBinary(FileChannel channel, InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        int read = in.read(buf);
        if (read == -1) throw new IllegalArgumentException("empty body");
        
        while (read != -1) {
            total += channel.write(ByteBuffer.wrap(buf, 0, read));
            read = in.read(buf);
        }
        return total;
    }

    @Override
    public boolean await(URI url, Offset startOffset, Duration timeout) throws Exception {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Optional<FileStreamMetadata> meta = metadataStore.get(url);
        if (meta.isPresent()) {
            long current = decodeOffset(meta.get().nextOffset());
            long required = decodeOffsetStrict(startOffset);
            if (current > required) return true;
        } else {
            return false;
        }

        ConcurrentLinkedQueue<CompletableFuture<Boolean>> queue = awaiters.computeIfAbsent(url, k -> new ConcurrentLinkedQueue<>());
        queue.add(future);

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.complete(false);
            return false;
        } finally {
            queue.remove(future);
        }
    }

    private void notifyWaiters(URI url) {
        ConcurrentLinkedQueue<CompletableFuture<Boolean>> queue = awaiters.get(url);
        if (queue != null) {
            CompletableFuture<Boolean> f;
            while ((f = queue.poll()) != null) {
                f.complete(true);
            }
        }
    }

    private JsonCodec requireJsonCodec() {
        return jsonCodec.orElseThrow(() ->
                new IllegalArgumentException("application/json requires an installed JSON codec module"));
    }

    private static Optional<JsonCodec> loadJsonCodec() {
        ServiceLoader<JsonCodec> loader = ServiceLoader.load(JsonCodec.class, Thread.currentThread().getContextClassLoader());
        Iterator<JsonCodec> iterator = loader.iterator();
        if (iterator.hasNext()) {
            return Optional.ofNullable(iterator.next());
        }
        return Optional.empty();
    }

    @Override
    public void close() throws IOException {
        metadataStore.close();
    }

    private void deleteStreamSync(URI url, Path dir) throws IOException {
        writeLocks.remove(url);
        awaiters.remove(url);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
            }
        }
    }

    private Path resolveStreamDir(URI url) {
        String hash = Integer.toHexString(url.toString().hashCode());
        return baseDir.resolve(hash);
    }

    private boolean isJson(String contentType) {
        if (contentType == null) return false;
        return contentType.toLowerCase().contains("application/json");
    }

    private String normalizeContentType(String ct) {
        if (ct == null) return "";
        int idx = ct.indexOf(";");
        return (idx >= 0 ? ct.substring(0, idx) : ct).trim().toLowerCase();
    }

    private long decodeOffset(Offset off) {
        if (off == null || "-1".equals(off.value())) return 0;
        try { return LexiLong.decode(off.value()); } catch (Exception e) { return 0; }
    }

    private long decodeOffsetStrict(Offset off) {
        if (off == null || "-1".equals(off.value())) return 0;
        String val = off.value();
        for (int i=0; i<val.length(); i++) {
            char c = val.charAt(i);
            if (!Character.isLetterOrDigit(c)) throw new IllegalArgumentException("invalid offset");
        }
        try { return LexiLong.decode(val); } catch (Exception e) { throw new IllegalArgumentException("invalid offset"); }
    }

    private String generateStreamId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private StreamMetadata toMetadata(FileStreamMetadata meta, Instant now) {
        Long ttl = meta.expiresAt() == null ? null : Math.max(0, Duration.between(now, meta.expiresAt()).getSeconds());
        return new StreamMetadata(meta.streamId(), meta.config(), meta.nextOffset(), ttl, meta.expiresAt());
    }

    private Instant resolveExpiresAt(StreamConfig config, Instant now) {
        if (config.ttlSeconds().isPresent()) return now.plusSeconds(config.ttlSeconds().get());
        return config.expiresAt().orElse(null);
    }

    private boolean sameConfig(StreamConfig a, StreamConfig b) {
        boolean ctMatch = normalizeContentType(a.contentType()).equals(normalizeContentType(b.contentType()));
        boolean ttlMatch = a.ttlSeconds().equals(b.ttlSeconds());
        boolean expMatch = a.expiresAt().equals(b.expiresAt());
        return ctMatch && ttlMatch && expMatch;
    }
}
