package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.*;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reference in-memory {@link StreamStore}.
 *
 * <p>Good for unit tests and examples. Not intended for production.
 *
 * <p>Content-Type behavior is delegated to {@link StreamCodec} implementations discovered via
 * {@link ServiceLoaderCodecRegistry}:
 * <ul>
 *   <li>All non-JSON content types use a built-in byte codec</li>
 *   <li>{@code application/json} requires an installed JSON codec module (e.g. durable-streams-json-jackson)</li>
 * </ul>
 */
public final class InMemoryStreamStore implements StreamStore {

    private static final int DEFAULT_MAX_BYTES = 64 * 1024;
    private static final int DEFAULT_MAX_MESSAGES = 1024;

    private final Map<URI, StreamState> streams = new ConcurrentHashMap<>();
    private final ServiceLoaderCodecRegistry codecs;

    public InMemoryStreamStore() {
        this(ServiceLoaderCodecRegistry.defaultRegistry());
    }

    public InMemoryStreamStore(ServiceLoaderCodecRegistry codecs) {
        this.codecs = Objects.requireNonNull(codecs, "codecs");
    }

    @Override
    public CreateOutcome create(URI url, StreamConfig config, InputStream initialBody) throws Exception {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(config, "config");

        StreamState s = streams.compute(url, (u, existing) -> {
            if (existing != null) return existing;
            return StreamState.createNew(config, codecs);
        });

        return s.createOutcomeFor(url, config, initialBody);
    }

    @Override
    public AppendOutcome append(URI url, String contentType, String streamSeq, InputStream body) throws Exception {
        StreamState s = streams.get(url);
        if (s == null) return new AppendOutcome(AppendOutcome.Status.NOT_FOUND, null, "stream not found");
        if (contentType == null || contentType.isBlank()) return new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, "missing Content-Type");
        if (!s.meta.config().contentType().equals(contentType)) {
            return new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "content-type mismatch");
        }
        if (body == null) return new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, "empty body");
        return s.append(streamSeq, body);
    }

    @Override
    public boolean delete(URI url) {
        return streams.remove(url) != null;
    }

    @Override
    public Optional<StreamMetadata> head(URI url) {
        StreamState s = streams.get(url);
        return s == null ? Optional.empty() : Optional.of(s.snapshotMeta());
    }

    @Override
    public ReadOutcome read(URI url, Offset startOffset, int maxBytesOrMessages) throws Exception {
        StreamState s = streams.get(url);
        if (s == null) return new ReadOutcome(ReadOutcome.Status.NOT_FOUND, null, null, null, false, null, null);

        int limit = maxBytesOrMessages <= 0 ? (s.isJson() ? DEFAULT_MAX_MESSAGES : DEFAULT_MAX_BYTES) : maxBytesOrMessages;
        return s.read(startOffset, limit);
    }

    @Override
    public boolean await(URI url, Offset startOffset, Duration timeout) throws Exception {
        StreamState s = streams.get(url);
        return s != null && s.await(startOffset, timeout);
    }

    private static boolean sameConfig(StreamConfig a, StreamConfig b) {
        return a.contentType().equals(b.contentType())
                && a.ttlSeconds().equals(b.ttlSeconds())
                && a.expiresAt().equals(b.expiresAt());
    }

    private static final class StreamState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition dataArrived = lock.newCondition();

        private final StreamMetadata meta;
        private final StreamCodec codec;
        private final StreamCodec.State state;
        private Offset nextOffset;

        private String lastSeq;
        private boolean createdOnce;

        private StreamState(StreamMetadata meta, StreamCodec codec, StreamCodec.State state, Offset nextOffset) {
            this.meta = meta;
            this.codec = codec;
            this.state = state;
            this.nextOffset = nextOffset;
        }

        static StreamState createNew(StreamConfig config, ServiceLoaderCodecRegistry codecs) {
            String ct = config.contentType();
            StreamCodec codec;
            if ("application/json".equalsIgnoreCase(ct)) {
                codec = codecs.find(ct).orElseThrow(() ->
                        new IllegalArgumentException("application/json requires an installed JSON codec module"));
            } else {
                codec = codecs.fallbackBytes();
            }
            StreamCodec.State st = codec.createEmpty();
            Offset next = new Offset(LexiLong.encode(0));
            StreamMetadata meta = new StreamMetadata(UUID.randomUUID().toString().replace("-", ""), config, next, null, config.expiresAt().orElse(null));
            return new StreamState(meta, codec, st, next);
        }

        boolean isJson() {
            return "application/json".equalsIgnoreCase(meta.config().contentType());
        }

        StreamMetadata snapshotMeta() {
            return new StreamMetadata(meta.internalStreamId(), meta.config(), nextOffset, meta.ttlSecondsRemaining().orElse(null), meta.expiresAt().orElse(null));
        }

        CreateOutcome createOutcomeFor(URI url, StreamConfig requested, InputStream initialBody) throws Exception {
            if (!sameConfig(meta.config(), requested)) {
                return new CreateOutcome(CreateOutcome.Status.EXISTS_CONFLICT, snapshotMeta(), nextOffset);
            }

            // Apply initial body only if stream is empty (size==0).
            if (codec.size(state) == 0 && initialBody != null) {
                codec.applyInitial(state, initialBody);
                nextOffset = new Offset(LexiLong.encode(codec.size(state)));
                dataArrived.signalAll();
            }

            boolean first = !createdOnce;
            createdOnce = true;
            return new CreateOutcome(first ? CreateOutcome.Status.CREATED : CreateOutcome.Status.EXISTS_MATCH, snapshotMeta(), nextOffset);
}

        AppendOutcome append(String streamSeq, InputStream body) throws Exception {
            lock.lock();
            try {
                if (streamSeq != null) {
                    if (lastSeq != null && streamSeq.compareTo(lastSeq) <= 0) {
                        return new AppendOutcome(AppendOutcome.Status.CONFLICT, null, "Stream-Seq regression");
                    }
                    lastSeq = streamSeq;
                }

                try {
                    codec.append(state, body);
                } catch (IllegalArgumentException iae) {
                    return new AppendOutcome(AppendOutcome.Status.BAD_REQUEST, null, iae.getMessage());
                }

                nextOffset = new Offset(LexiLong.encode(codec.size(state)));
                dataArrived.signalAll();
                return new AppendOutcome(AppendOutcome.Status.APPENDED, nextOffset, null);
            } finally {
                lock.unlock();
            }
        }

        ReadOutcome read(Offset startOffset, int limit) throws Exception {
            long pos = decodeStart(startOffset);
            if (pos < 0) return new ReadOutcome(ReadOutcome.Status.BAD_REQUEST, null, null, null, false, null, null);

            long tail = codec.size(state);
            long start = Math.min(pos, tail);

            StreamCodec.ReadChunk chunk = codec.read(state, start, limit);
            Offset next = new Offset(LexiLong.encode(chunk.nextPosition()));
            String etag = meta.internalStreamId() + ":" + LexiLong.encode(start) + ":" + next.value();

            return new ReadOutcome(ReadOutcome.Status.OK, chunk.body(), meta.config().contentType(), next, chunk.upToDate(), etag, null);
        }

        boolean await(Offset startOffset, Duration timeout) throws Exception {
            long pos = decodeStart(startOffset);
            lock.lock();
            try {
                long tail = codec.size(state);
                if (pos < tail) return true;

                long nanos = timeout.toNanos();
                while (nanos > 0) {
                    nanos = dataArrived.awaitNanos(nanos);
                    long newTail = codec.size(state);
                    if (pos < newTail) return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        private static long decodeStart(Offset off) {
            if (off == null) return 0;
            if ("-1".equals(off.value())) return 0;
            try { return LexiLong.decode(off.value()); }
            catch (Exception e) { return -1; }
        }
    }
}
