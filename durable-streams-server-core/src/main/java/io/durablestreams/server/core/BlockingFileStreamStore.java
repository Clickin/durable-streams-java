package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.core.metadata.FileStreamMetadata;
import io.durablestreams.server.core.metadata.LmdbMetadataStore;
import io.durablestreams.server.core.metadata.MetadataStore;
import io.durablestreams.server.spi.*;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int DEFAULT_APPEND_SHARDS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_APPEND_QUEUE_CAPACITY = 4096;
    private static final int DEFAULT_APPEND_BATCH_SIZE = 64;
    private static final int DEFAULT_FILE_HANDLE_POOL_SIZE = 256;
    private static final int APPEND_BUFFER_SIZE = 8192;
    private static final byte[] NEWLINE_BYTES = new byte[] { '\n' };
    private static final String DATA_FILE = "data.bin";

    private final Path baseDir;
    private final Clock clock;
    private final MetadataStore metadataStore;
    private final StreamCodecRegistry codecs;

    // Per-stream state for coordinating waiters
    private final ConcurrentHashMap<URI, StreamState> streams = new ConcurrentHashMap<>();
    private final AppendDispatcher appendDispatcher;


    public BlockingFileStreamStore(Path baseDir) {
        this(baseDir, Clock.systemUTC(), null, ServiceLoaderCodecRegistry.defaultRegistry());
    }

    public BlockingFileStreamStore(Path baseDir, StreamCodecRegistry codecs) {
        this(baseDir, Clock.systemUTC(), null, codecs);
    }

    public BlockingFileStreamStore(Path baseDir, Clock clock) {
        this(baseDir, clock, null, ServiceLoaderCodecRegistry.defaultRegistry());
    }

    public BlockingFileStreamStore(Path baseDir, MetadataStore metadataStore) {
        this(baseDir, Clock.systemUTC(), metadataStore, ServiceLoaderCodecRegistry.defaultRegistry());
    }

    public BlockingFileStreamStore(Path baseDir, Clock clock, MetadataStore metadataStore) {
        this(baseDir, clock, metadataStore, ServiceLoaderCodecRegistry.defaultRegistry());
    }

    public BlockingFileStreamStore(Path baseDir, Clock clock, MetadataStore metadataStore, StreamCodecRegistry codecs) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metadataStore = metadataStore != null ? metadataStore : new LmdbMetadataStore(baseDir.resolve("metadata"));
        this.codecs = Objects.requireNonNull(codecs, "codecs");
        ensureDirectory(baseDir);
        this.appendDispatcher = new AppendDispatcher(
                DEFAULT_APPEND_SHARDS,
                DEFAULT_APPEND_QUEUE_CAPACITY,
                DEFAULT_APPEND_BATCH_SIZE,
                DEFAULT_FILE_HANDLE_POOL_SIZE
        );
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
        return appendDispatcher.append(url, contentType, streamSeq, body);
    }


    @Override
    public boolean delete(URI url) throws Exception {
        return appendDispatcher.delete(url);
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
        appendDispatcher.close();
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

    private final class AppendDispatcher implements AutoCloseable {
        private final AppendShard[] shards;
        private final int shardCount;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private AppendDispatcher(int shardCount, int queueCapacity, int batchSize, int fileHandlePoolSize) {
            this.shardCount = Math.max(1, shardCount);
            this.shards = new AppendShard[this.shardCount];
            for (int i = 0; i < this.shardCount; i++) {
                shards[i] = new AppendShard(i, queueCapacity, batchSize, fileHandlePoolSize);
            }
        }

        private AppendOutcome append(URI url, String contentType, String streamSeq, InputStream body) throws Exception {
            if (closed.get()) {
                throw new IllegalStateException("store is closed");
            }
            AppendTask task = new AppendTask(url, contentType, streamSeq, body);
            shardFor(url).enqueue(task);
            return task.await();
        }

        private boolean delete(URI url) throws Exception {
            if (closed.get()) {
                throw new IllegalStateException("store is closed");
            }
            DeleteTask task = new DeleteTask(url);
            shardFor(url).enqueue(task);
            return task.await();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (AppendShard shard : shards) {
                shard.shutdown();
            }
        }

        private AppendShard shardFor(URI url) {
            int shard = Math.floorMod(url.hashCode(), shardCount);
            return shards[shard];
        }
    }

    private interface ShardTask {}

    private static final class AppendTask implements ShardTask {
        private final URI url;
        private final String contentType;
        private final String streamSeq;
        private final InputStream body;
        private final CompletableFuture<AppendOutcome> future = new CompletableFuture<>();

        private AppendTask(URI url, String contentType, String streamSeq, InputStream body) {
            this.url = url;
            this.contentType = contentType;
            this.streamSeq = streamSeq;
            this.body = body;
        }

        private AppendOutcome await() throws Exception {
            return awaitFuture(future);
        }
    }

    private static final class DeleteTask implements ShardTask {
        private final URI url;
        private final CompletableFuture<Boolean> future = new CompletableFuture<>();

        private DeleteTask(URI url) {
            this.url = url;
        }

        private boolean await() throws Exception {
            return awaitFuture(future);
        }
    }

    private static final class CloseTask implements ShardTask {
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        private void await() throws Exception {
            awaitFuture(future);
        }
    }

    private static <T> T awaitFuture(CompletableFuture<T> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cause);
        }
    }

    private final class AppendShard implements Runnable {
        private final BlockingQueue<ShardTask> queue;
        private final int batchSize;
        private final FileHandlePool fileHandlePool;
        private final Map<URI, AppendState> appendStates = new HashMap<>();
        private final Thread thread;
        private volatile boolean running = true;

        private AppendShard(int shardId, int queueCapacity, int batchSize, int fileHandlePoolSize) {
            this.queue = new LinkedBlockingQueue<>(queueCapacity);
            this.batchSize = Math.max(1, batchSize);
            this.fileHandlePool = new FileHandlePool(fileHandlePoolSize);
            this.thread = new Thread(this, "durable-streams-append-" + shardId);
            this.thread.start();
        }

        private void enqueue(ShardTask task) throws InterruptedException {
            queue.put(task);
        }

        private void shutdown() {
            CloseTask task = new CloseTask();
            try {
                queue.put(task);
                task.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void run() {
            while (running) {
                ShardTask task;
                try {
                    task = queue.take();
                } catch (InterruptedException e) {
                    if (!running) {
                        return;
                    }
                    continue;
                }
                if (task instanceof CloseTask closeTask) {
                    handleClose(closeTask);
                    return;
                }
                if (task instanceof DeleteTask deleteTask) {
                    handleDelete(deleteTask);
                    continue;
                }
                AppendTask appendTask = (AppendTask) task;
                List<AppendTask> batch = new ArrayList<>(batchSize);
                batch.add(appendTask);
                while (batch.size() < batchSize) {
                    ShardTask next = queue.peek();
                    if (!(next instanceof AppendTask)) {
                        break;
                    }
                    AppendTask drained = (AppendTask) queue.poll();
                    if (drained == null) {
                        break;
                    }
                    batch.add(drained);
                }
                processAppendBatch(batch);
            }
        }

        private void handleClose(CloseTask closeTask) {
            running = false;
            List<ShardTask> remaining = new ArrayList<>();
            queue.drainTo(remaining);
            for (ShardTask task : remaining) {
                if (task instanceof AppendTask appendTask) {
                    appendTask.future.completeExceptionally(new IllegalStateException("store is closed"));
                } else if (task instanceof DeleteTask deleteTask) {
                    deleteTask.future.completeExceptionally(new IllegalStateException("store is closed"));
                }
            }
            fileHandlePool.closeAll();
            closeTask.future.complete(null);
        }

        private void handleDelete(DeleteTask task) {
            URI url = task.url;
            Path streamDir = resolveStreamDir(url);
            Path dataPath = streamDir.resolve(DATA_FILE);
            try {
                boolean removedMeta = metadataStore.delete(url);
                fileHandlePool.close(dataPath);
                appendStates.remove(url);
                if (!Files.exists(streamDir)) {
                    StreamState state = streams.remove(url);
                    if (state != null) {
                        state.notifyAll_();
                    }
                    task.future.complete(removedMeta);
                    return;
                }
                deleteStreamDir(streamDir);
                StreamState state = streams.remove(url);
                if (state != null) {
                    state.notifyAll_();
                }
                task.future.complete(true);
            } catch (Exception e) {
                task.future.completeExceptionally(e);
            }
        }

        private void processAppendBatch(List<AppendTask> batch) {
            Map<URI, StreamBatch> batches = new HashMap<>();
            for (AppendTask task : batch) {
                try {
                    handleAppendTask(task, batches);
                } catch (Exception e) {
                    task.future.completeExceptionally(e);
                }
            }
            for (StreamBatch streamBatch : batches.values()) {
                if (!streamBatch.hasSuccesses()) {
                    continue;
                }
                try {
                    if (streamBatch.wroteData) {
                        streamBatch.channel.force(false);
                    }
                    metadataStore.put(streamBatch.url, streamBatch.finalMeta());
                    streamBatch.state.updateAndNotify(streamBatch.finalMeta().nextOffset());
                    for (AppendResult result : streamBatch.successes) {
                        result.task.future.complete(new AppendOutcome(AppendOutcome.Status.APPENDED, result.nextOffset, null));
                    }
                } catch (Exception e) {
                    failBatch(streamBatch, e);
                }
            }
        }

        private void handleAppendTask(AppendTask task, Map<URI, StreamBatch> batches) throws Exception {
            StreamBatch batch = batches.get(task.url);
            if (batch == null) {
                Optional<FileStreamMetadata> metaOpt = metadataStore.get(task.url);
                if (metaOpt.isEmpty()) {
                    task.future.complete(new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream not found"));
                    return;
                }
                FileStreamMetadata meta = metaOpt.get();
                Instant now = clock.instant();
                if (meta.isExpired(now)) {
                    expireStream(task.url);
                    task.future.complete(new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream expired"));
                    return;
                }
                if (!normalizeContentType(meta.config().contentType()).equals(normalizeContentType(task.contentType))) {
                    task.future.complete(new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "content-type mismatch"));
                    return;
                }
                Path streamDir = resolveStreamDir(task.url);
                Path dataPath = streamDir.resolve(DATA_FILE);
                if (!Files.exists(dataPath)) {
                    task.future.complete(new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream data missing"));
                    return;
                }
                StreamState state = streams.computeIfAbsent(task.url, u -> new StreamState(meta.nextOffset()));
                if (!state.checkAndUpdateSeq(task.streamSeq)) {
                    task.future.complete(new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "Stream-Seq regression"));
                    return;
                }
                batch = newStreamBatch(task.url, meta, state, dataPath);
                batches.put(task.url, batch);
            } else {
                if (!normalizeContentType(batch.meta.config().contentType()).equals(normalizeContentType(task.contentType))) {
                    task.future.complete(new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "content-type mismatch"));
                    return;
                }
                if (!batch.state.checkAndUpdateSeq(task.streamSeq)) {
                    task.future.complete(new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "Stream-Seq regression"));
                    return;
                }
            }
            batch.append(task);
        }

        private StreamBatch newStreamBatch(URI url, FileStreamMetadata meta, StreamState state, Path dataPath) throws IOException {
            AppendState appendState = appendStates.get(url);
            boolean json = isJsonContentType(meta.config().contentType());
            if (appendState == null || appendState.json != json) {
                appendState = initializeAppendState(meta, dataPath);
                appendStates.put(url, appendState);
            }
            FileChannel channel = fileHandlePool.open(dataPath);
            channel.position(appendState.filePosition);
            return new StreamBatch(url, meta, state, appendState, dataPath, channel);
        }

        private AppendState initializeAppendState(FileStreamMetadata meta, Path dataPath) throws IOException {
            boolean json = isJsonContentType(meta.config().contentType());
            long fileSize = Files.size(dataPath);
            long offsetPosition = json ? countLines(dataPath) : fileSize;
            return new AppendState(json, offsetPosition, fileSize);
        }

        private void expireStream(URI url) throws IOException {
            Path streamDir = resolveStreamDir(url);
            Path dataPath = streamDir.resolve(DATA_FILE);
            fileHandlePool.close(dataPath);
            appendStates.remove(url);
            deleteStreamDir(streamDir);
            metadataStore.delete(url);
            streams.remove(url);
        }

        private void failBatch(StreamBatch batch, Exception e) {
            fileHandlePool.close(batch.dataPath);
            appendStates.remove(batch.url);
            for (AppendResult result : batch.successes) {
                result.task.future.completeExceptionally(e);
            }
        }
    }

    private static final class AppendState {
        private final boolean json;
        private long offsetPosition;
        private long filePosition;

        private AppendState(boolean json, long offsetPosition, long filePosition) {
            this.json = json;
            this.offsetPosition = offsetPosition;
            this.filePosition = filePosition;
        }
    }

    private final class StreamBatch {
        private final URI url;
        private final StreamState state;
        private final AppendState appendState;
        private final Path dataPath;
        private final FileChannel channel;
        private FileStreamMetadata meta;
        private final List<AppendResult> successes = new ArrayList<>();
        private boolean wroteData;

        private StreamBatch(URI url, FileStreamMetadata meta, StreamState state, AppendState appendState, Path dataPath, FileChannel channel) {
            this.url = url;
            this.meta = meta;
            this.state = state;
            this.appendState = appendState;
            this.dataPath = dataPath;
            this.channel = channel;
        }

        private void append(AppendTask task) throws Exception {
            if (appendState.json) {
                byte[] bytes = readAllBytes(task.body);
                if (bytes.length == 0) {
                    task.future.complete(new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, "empty body"));
                    return;
                }
                List<byte[]> messages;
                try {
                    messages = parseAndFlattenJsonMessages(bytes);
                } catch (IllegalArgumentException e) {
                    task.future.complete(new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, e.getMessage()));
                    return;
                }
                long bytesWritten = appendJsonMessages(channel, messages);
                appendState.filePosition += bytesWritten;
                appendState.offsetPosition += messages.size();
                Offset nextOffset = new Offset(LexiLong.encode(appendState.offsetPosition));
                markSuccess(task, nextOffset, bytesWritten > 0);
                return;
            }
            long bytesWritten = appendBinary(channel, task.body);
            appendState.filePosition += bytesWritten;
            appendState.offsetPosition += bytesWritten;
            Offset nextOffset = new Offset(LexiLong.encode(appendState.offsetPosition));
            markSuccess(task, nextOffset, bytesWritten > 0);
        }

        private void markSuccess(AppendTask task, Offset nextOffset, boolean wroteDataNow) {
            meta = meta.withNextOffset(nextOffset);
            successes.add(new AppendResult(task, nextOffset));
            if (wroteDataNow) {
                wroteData = true;
            }
        }

        private boolean hasSuccesses() {
            return !successes.isEmpty();
        }

        private FileStreamMetadata finalMeta() {
            return meta;
        }

        private long appendBinary(FileChannel channel, InputStream body) throws IOException {
            byte[] buffer = new byte[APPEND_BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) != -1) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, read);
                while (byteBuffer.hasRemaining()) {
                    total += channel.write(byteBuffer);
                }
            }
            return total;
        }

        private long appendJsonMessages(FileChannel channel, List<byte[]> messages) throws IOException {
            long total = 0;
            for (byte[] message : messages) {
                total += writeFully(channel, message);
                total += writeFully(channel, NEWLINE_BYTES);
            }
            return total;
        }

        private long writeFully(FileChannel channel, byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long total = 0;
            while (buffer.hasRemaining()) {
                total += channel.write(buffer);
            }
            return total;
        }
    }

    private static final class AppendResult {
        private final AppendTask task;
        private final Offset nextOffset;

        private AppendResult(AppendTask task, Offset nextOffset) {
            this.task = task;
            this.nextOffset = nextOffset;
        }
    }

    private static final class FileHandlePool {
        private final int maxOpenFiles;
        private final LinkedHashMap<Path, FileChannel> channels = new LinkedHashMap<>(16, 0.75f, true);

        private FileHandlePool(int maxOpenFiles) {
            this.maxOpenFiles = Math.max(1, maxOpenFiles);
        }

        private FileChannel open(Path path) throws IOException {
            FileChannel channel = channels.get(path);
            if (channel == null || !channel.isOpen()) {
                channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                channels.put(path, channel);
                evictIfNeeded();
            }
            return channel;
        }

        private void close(Path path) {
            FileChannel channel = channels.remove(path);
            closeQuietly(channel);
        }

        private void closeAll() {
            for (FileChannel channel : channels.values()) {
                closeQuietly(channel);
            }
            channels.clear();
        }

        private void evictIfNeeded() {
            while (channels.size() > maxOpenFiles) {
                Iterator<Map.Entry<Path, FileChannel>> iterator = channels.entrySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                Map.Entry<Path, FileChannel> eldest = iterator.next();
                iterator.remove();
                closeQuietly(eldest.getValue());
            }
        }

        private void closeQuietly(FileChannel channel) {
            if (channel == null) {
                return;
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                return;
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

    private long writeJsonMessages(Path dataPath, List<byte[]> messages) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(dataPath, java.nio.charset.StandardCharsets.UTF_8)) {
            for (byte[] msg : messages) {
                writer.write(new String(msg, java.nio.charset.StandardCharsets.UTF_8));
                writer.newLine();
            }
        }
        return messages.size();
    }

    private static long countLines(Path dataPath) throws IOException {
        long count = 0;
        boolean sawByte = false;
        int lastByte = -1;
        byte[] buffer = new byte[APPEND_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(dataPath)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                sawByte = true;
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\n') {
                        count++;
                    }
                }
                lastByte = buffer[read - 1];
            }
        }
        if (sawByte && lastByte != '\n') {
            count++;
        }
        return count;
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
