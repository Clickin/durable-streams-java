package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.core.metadata.FileStreamMetadata;
import io.durablestreams.server.core.metadata.LmdbMetadataStore;
import io.durablestreams.server.core.metadata.MetadataStore;
import io.durablestreams.server.spi.*;

import java.io.*;
import java.net.URI;
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
 * <p>This implementation uses blocking file channels for append and memory-mapped reads.
 * It is designed to pair with virtual threads for high concurrency.
 *
 * <p>Metadata is stored in LMDB under {@code baseDir/metadata}.
 *
 * <p>Storage layout:
 * <pre>
 * baseDir/
 *   metadata/        - LMDB metadata database
 *   {stream-id}/
 *     data.bin       - stream content (binary)
 * </pre>
 *
 * @see StreamStore

 */
public final class BlockingFileStreamStore implements StreamStore {

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
    private static final String DATA_FILE = "data.bin";

    private final Path baseDir;
    private final Clock clock;
    private final MetadataStore metadataStore;
    private final ServiceLoaderCodecRegistry codecs;

    // Per-stream state for coordinating waiters
    private final ConcurrentHashMap<URI, StreamState> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<URI, ReentrantLock> appendLocks = new ConcurrentHashMap<>();


    public BlockingFileStreamStore(Path baseDir) {
        this(baseDir, Clock.systemUTC());
    }

    public BlockingFileStreamStore(Path baseDir, Clock clock) {
        this(baseDir, clock, new LmdbMetadataStore(baseDir.resolve("metadata")));
    }

    public BlockingFileStreamStore(Path baseDir, MetadataStore metadataStore) {
        this(baseDir, Clock.systemUTC(), metadataStore);
    }

    public BlockingFileStreamStore(Path baseDir, Clock clock, MetadataStore metadataStore) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore");
        this.codecs = ServiceLoaderCodecRegistry.defaultRegistry();
        ensureDirectory(baseDir);
    }


    @Override
    public CreateOutcome create(URI url, StreamConfig config, InputStream initialBody) throws Exception {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(config, "config");

        Path streamDir = resolveStreamDir(url);
        Path dataPath = streamDir.resolve(DATA_FILE);

        Instant now = clock.instant();

        Optional<FileStreamMetadata> existing = metadataStore.get(url);
        if (existing.isPresent()) {
            FileStreamMetadata existingMeta = existing.get();
            if (existingMeta.isExpired(now)) {
                deleteStreamDir(streamDir);
                metadataStore.delete(url);
            } else {
                if (!sameConfig(existingMeta.config(), config)) {
                    return new CreateOutcome(
                            CreateOutcome.Status.EXISTS_CONFLICT,
                            toMetadata(existingMeta, now),
                            existingMeta.nextOffset()
                    );
                }
                return new CreateOutcome(
                        CreateOutcome.Status.EXISTS_MATCH,
                        toMetadata(existingMeta, now),
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
            if (isJsonContentType(config.contentType())) {
                byte[] bytes = readAllBytes(initialBody);
                List<byte[]> messages = parseJsonInitial(bytes);
                dataSize = writeJsonMessages(dataPath, messages);
            } else {
                dataSize = writeData(dataPath, initialBody);
            }
        } else {
            Files.createFile(dataPath);
        }

        Offset nextOffset = new Offset(LexiLong.encode(dataSize));

        FileStreamMetadata meta = new FileStreamMetadata(streamId, config, nextOffset, expiresAt);
        metadataStore.put(url, meta);

        // Register in memory for await coordination
        streams.put(url, new StreamState(nextOffset));

        return new CreateOutcome(
                CreateOutcome.Status.CREATED,
                toMetadata(meta, now),
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
        Path dataPath = streamDir.resolve(DATA_FILE);

        ReentrantLock appendLock = appendLocks.computeIfAbsent(url, u -> new ReentrantLock());
        appendLock.lock();
        try {
            Optional<FileStreamMetadata> metaOpt = metadataStore.get(url);
            if (metaOpt.isEmpty()) {
                return new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream not found");
            }

            FileStreamMetadata meta = metaOpt.get();
            Instant now = clock.instant();

            if (meta.isExpired(now)) {
                deleteStreamDir(streamDir);
                metadataStore.delete(url);
                streams.remove(url);
                appendLocks.remove(url);
                return new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream expired");
            }

            if (!normalizeContentType(meta.config().contentType()).equals(normalizeContentType(contentType))) {
                return new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "content-type mismatch");
            }

            if (!Files.exists(dataPath)) {
                return new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream data missing");
            }

            StreamState state = streams.computeIfAbsent(url, u -> new StreamState(meta.nextOffset()));
            if (!state.checkAndUpdateSeq(streamSeq)) {
                return new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "Stream-Seq regression");
            }

            long newSize;
            if (isJsonContentType(contentType)) {
                byte[] bytes = readAllBytes(body);
                if (bytes.length == 0) {
                    return new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, "empty body");
                }
                List<byte[]> messages;
                try {
                    messages = parseAndFlattenJsonMessages(bytes);
                } catch (IllegalArgumentException e) {
                    return new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, e.getMessage());
                }
                newSize = appendJsonMessages(dataPath, messages);
            } else {
                newSize = appendStream(dataPath, body);
            }

            Offset nextOffset = new Offset(LexiLong.encode(newSize));

            FileStreamMetadata newMeta = meta.withNextOffset(nextOffset);
            metadataStore.put(url, newMeta);

            state.updateAndNotify(nextOffset);

            return new AppendOutcome(AppendOutcome.Status.APPENDED, nextOffset, null);
        } finally {
            appendLock.unlock();
        }

    }

    @Override
    public boolean delete(URI url) throws Exception {
        Path streamDir = resolveStreamDir(url);
        boolean removedMeta = metadataStore.delete(url);
        if (!Files.exists(streamDir)) {
            return removedMeta;
        }
        deleteStreamDir(streamDir);
        appendLocks.remove(url);
        StreamState state = streams.remove(url);
        if (state != null) {
            state.notifyAll_();
        }
        return true;

    }

    @Override
    public Optional<StreamMetadata> head(URI url) throws Exception {
        Path streamDir = resolveStreamDir(url);
        Optional<FileStreamMetadata> metaOpt = metadataStore.get(url);
        if (metaOpt.isEmpty()) {
            return Optional.empty();
        }

        FileStreamMetadata meta = metaOpt.get();
        Instant now = clock.instant();

        if (meta.isExpired(now)) {
            deleteStreamDir(streamDir);
            metadataStore.delete(url);
            streams.remove(url);
            return Optional.empty();
        }

        return Optional.of(toMetadata(meta, now));

    }

    @Override
    public ReadOutcome read(URI url, Offset startOffset, int maxBytesOrMessages) throws Exception {
        Path streamDir = resolveStreamDir(url);
        Path dataPath = streamDir.resolve(DATA_FILE);

        Optional<FileStreamMetadata> metaOpt = metadataStore.get(url);
        if (metaOpt.isEmpty()) {
            return new ReadOutcome(ReadOutcome.Status.NOT_FOUND, null, null, null, false, null, null);
        }

        FileStreamMetadata meta = metaOpt.get();
        Instant now = clock.instant();

        if (meta.isExpired(now)) {
            deleteStreamDir(streamDir);
            metadataStore.delete(url);
            streams.remove(url);
            return new ReadOutcome(ReadOutcome.Status.NOT_FOUND, null, null, null, false, null, null);
        }

        if (!Files.exists(dataPath)) {
            return new ReadOutcome(ReadOutcome.Status.NOT_FOUND, null, null, null, false, null, null);
        }

        long pos = decodeOffset(startOffset);
        if (pos < 0) {
            return new ReadOutcome(ReadOutcome.Status.BAD_REQUEST, null, null, null, false, null, null);
        }

        if (isJsonContentType(meta.config().contentType())) {
            return readJson(dataPath, meta, pos, maxBytesOrMessages);
        }

        return readBinary(dataPath, meta, pos, maxBytesOrMessages);
    }

    private ReadOutcome readBinary(Path dataPath, FileStreamMetadata meta, long pos, int maxBytesOrMessages) throws IOException {
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

        ReadOutcome.FileRegion region = new ReadOutcome.FileRegion(dataPath, readPos, toRead);
        long nextPos = readPos + toRead;
        boolean upToDate = nextPos >= fileSize;
        Offset nextOffset = new Offset(LexiLong.encode(nextPos));
        String etag = meta.streamId() + ":" + LexiLong.encode(readPos) + ":" + nextOffset.value();

        return new ReadOutcome(
                ReadOutcome.Status.OK,
                null,
                meta.config().contentType(),
                nextOffset,
                upToDate,
                etag,
                null,
                region
        );
    }

    private ReadOutcome readJson(Path dataPath, FileStreamMetadata meta, long pos, int maxMessages) throws IOException {
        List<String> allMessages = Files.readAllLines(dataPath, java.nio.charset.StandardCharsets.UTF_8);
        int startIdx = (int) Math.min(pos, allMessages.size());
        int limit = maxMessages <= 0 ? 1024 : maxMessages;
        int endIdx = (int) Math.min(allMessages.size(), (long) startIdx + limit);
        boolean upToDate = endIdx >= allMessages.size();

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = startIdx; i < endIdx; i++) {
            if (i > startIdx) sb.append(',');
            sb.append(allMessages.get(i));
        }
        sb.append(']');

        byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Offset nextOffset = new Offset(LexiLong.encode(endIdx));
        String etag = meta.streamId() + ":" + LexiLong.encode(startIdx) + ":" + nextOffset.value();

        return new ReadOutcome(
                ReadOutcome.Status.OK,
                body,
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
            Optional<FileStreamMetadata> metaOpt = metadataStore.get(url);
            if (metaOpt.isEmpty()) {
                return false;
            }
            FileStreamMetadata meta = metaOpt.get();
            if (meta.isExpired(clock.instant())) {
                return false;
            }
            state = streams.computeIfAbsent(url, u -> new StreamState(meta.nextOffset()));
        }

        long pos = decodeOffset(startOffset);
        return state.awaitData(pos, timeout);
    }

    public void close() {
        try {
            metadataStore.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- Internal state for await coordination ---


    private static final class StreamState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition dataArrived = lock.newCondition();
        private volatile long currentPosition;
        private String lastSeq;

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

        boolean checkAndUpdateSeq(String streamSeq) {
            if (streamSeq == null) {
                return true;
            }
            lock.lock();
            try {
                if (lastSeq != null && streamSeq.compareTo(lastSeq) <= 0) {
                    return false;
                }
                lastSeq = streamSeq;
                return true;
            } finally {
                lock.unlock();
            }
        }
    }

    // --- Metadata handling ---

    private StreamMetadata toMetadata(FileStreamMetadata meta, Instant now) {
        Long ttlRemaining = null;
        if (meta.expiresAt() != null) {
            long seconds = Duration.between(now, meta.expiresAt()).getSeconds();
            ttlRemaining = Math.max(seconds, 0L);
        }
        return new StreamMetadata(meta.streamId(), meta.config(), meta.nextOffset(), ttlRemaining, meta.expiresAt());
    }

    private List<byte[]> parseJsonInitial(byte[] bytes) {
        if (bytes.length == 0) {
            return List.of();
        }
        StreamCodec codec = requireJsonCodec();
        StreamCodec.State state = codec.createEmpty();
        try {
            codec.applyInitial(state, new ByteArrayInputStream(bytes));
            return extractMessagesFromCodecState(codec, state);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json", e);
        }
    }

    private List<byte[]> parseAndFlattenJsonMessages(byte[] bytes) {
        StreamCodec codec = requireJsonCodec();
        StreamCodec.State state = codec.createEmpty();
        try {
            codec.append(state, new ByteArrayInputStream(bytes));
            return extractMessagesFromCodecState(codec, state);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json", e);
        }
    }

    private List<byte[]> extractMessagesFromCodecState(StreamCodec codec, StreamCodec.State state) throws Exception {
        long size = codec.size(state);
        List<byte[]> messages = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            StreamCodec.ReadChunk chunk = codec.read(state, i, 1);
            String json = new String(chunk.body(), java.nio.charset.StandardCharsets.UTF_8);
            if (json.startsWith("[") && json.endsWith("]")) {
                json = json.substring(1, json.length() - 1);
            }
            messages.add(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return messages;
    }

    private StreamCodec requireJsonCodec() {
        return codecs.find("application/json").orElseThrow(() ->
                new IllegalArgumentException("application/json requires an installed JSON codec module"));
    }

    private static boolean isJsonContentType(String contentType) {
        if (contentType == null) return false;
        int semi = contentType.indexOf(';');
        String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return "application/json".equalsIgnoreCase(base.trim());
    }

    private static byte[] readAllBytes(InputStream body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = body.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
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

    private long writeData(Path dataPath, byte[] bytes) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dataPath.toFile()))) {
            out.write(bytes);
            return bytes.length;
        }
    }

    private long appendStream(Path dataPath, InputStream body) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "rw")) {
            raf.seek(raf.length());
            byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) != -1) {
                raf.write(buffer, 0, read);
            }
            return raf.length();
        }
    }

    private long appendBytes(Path dataPath, byte[] bytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "rw")) {
            raf.seek(raf.length());
            raf.write(bytes);
            return raf.length();
        }
    }

    private long writeJsonMessages(Path dataPath, List<byte[]> messages) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(dataPath, java.nio.charset.StandardCharsets.UTF_8)) {
            for (byte[] msg : messages) {
                writer.write(new String(msg, java.nio.charset.StandardCharsets.UTF_8));
                writer.newLine();
            }
        }
        return messages.size();
    }

    private long appendJsonMessages(Path dataPath, List<byte[]> messages) throws IOException {
        long lineCount;
        try (var lines = Files.lines(dataPath)) {
            lineCount = lines.count();
        }
        try (BufferedWriter writer = Files.newBufferedWriter(dataPath, java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)) {
            for (byte[] msg : messages) {
                writer.write(new String(msg, java.nio.charset.StandardCharsets.UTF_8));
                writer.newLine();
            }
        }
        return lineCount + messages.size();
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
