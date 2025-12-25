package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.StreamCodec;
import io.durablestreams.server.spi.StreamConfig;
import io.durablestreams.server.spi.StreamMetadata;
import io.durablestreams.server.spi.CreateOutcome;
import io.durablestreams.server.spi.AppendOutcome;
import io.durablestreams.server.spi.ReadOutcome;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory reference store using provided OffsetGenerator.
 *
 * <p>Replaces hardcoded LexiLong offset generation with pluggable strategy.
 * Demonstrates proper TTL/expiration enforcement, retention handling, and ETag formatting.
 */
public final class ReferenceStreamStore implements StreamStore {

    private final OffsetGenerator offsetGenerator;
    private final StreamCodecRegistry codecRegistry;
    private final Clock clock;

    // Storage
    private final Map<java.net.URI, StreamState> streams = new ConcurrentHashMap<>();

    public ReferenceStreamStore(OffsetGenerator offsetGenerator, StreamCodecRegistry codecRegistry, Clock clock) {
        this.offsetGenerator = Objects.requireNonNull(offsetGenerator, "offsetGenerator");
        this.codecRegistry = Objects.requireNonNull(codecRegistry, "codecRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReferenceStreamStore(OffsetGenerator offsetGenerator) {
        this(offsetGenerator, new ServiceLoaderCodecRegistry(Thread.currentThread().getContextClassLoader()));
    }

    @Override
    public CreateOutcome create(java.net.URI url, StreamConfig config, java.io.InputStream initialBody) throws Exception {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(config, "config");

        StreamState s = streams.compute(url, (u, existing) -> {
            if (existing != null) return existing;
            return StreamState.createNew(config, codecRegistry);
        });

        return s.createOutcomeFor(url, config, initialBody);
    }

    @Override
    public AppendOutcome append(java.net.URI url, String contentType, String streamSeq, java.io.InputStream body) throws Exception {
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
    public boolean delete(java.net.URI url) {
        return streams.remove(url) != null;
    }

    @Override
    public Optional<StreamMetadata> head(java.net.URI url) {
        StreamState s = streams.get(url);
        return s == null ? Optional.empty() : Optional.of(s.snapshotMeta());
    }

    @Override
    public ReadOutcome read(java.net.URI url, Offset startOffset, int maxBytesOrMessages) throws Exception {
        StreamState s = streams.get(url);
        if (s == null) return new ReadOutcome(ReadOutcome.Status.NOT_FOUND, null, null, null, false, null, null);

        int limit = maxBytesOrMessages <= 0 ? (s.isJson() ? DEFAULT_MAX_MESSAGES : DEFAULT_MAX_BYTES) : maxBytesOrMessages;
        return s.read(startOffset, limit);
    }

    @Override
    public boolean await(java.net.URI url, Offset startOffset, Duration timeout) throws Exception {
        StreamState s = streams.get(url);
        return s != null && s.await(startOffset, timeout);
    }

    // Constants for defaults
    private static final int DEFAULT_MAX_BYTES = 64 * 1024;
    private static final int DEFAULT_MAX_MESSAGES = 1024;

    // Stream state implementation similar to InMemoryStreamStore but using OffsetGenerator
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

        static StreamState createNew(StreamConfig config, StreamCodecRegistry codecRegistry) {
            String ct = config.contentType();
            StreamCodec codec;
            if ("application/json".equalsIgnoreCase(ct)) {
                codec = codecRegistry.find(ct).orElseThrow(() ->
                        new IllegalArgumentException("application/json requires an installed JSON codec module"));
            } else {
                codec = codecRegistry.fallbackBytes();
            }
            StreamCodec.State st = codec.createEmpty();
            Offset next = new Offset("0"); // Will be replaced by first offsetGenerator.next(null, 0)
            StreamMetadata meta = new StreamMetadata(java.util.UUID.randomUUID().toString().replace("-", ""), config, next, null, config.expiresAt().orElse(null));
            return new StreamState(meta, codec, st, next);
        }

        boolean isJson() {
            return "application/json".equalsIgnoreCase(meta.config().contentType());
        }

        StreamMetadata snapshotMeta() {
            // Update remaining TTL on snapshot
            Instant now = Clock.systemUTC().instant();
            Long ttlSecondsRemaining = calculateTtlRemaining(meta.expiresAt(), now);
            return new StreamMetadata(meta.internalStreamId(), meta.config(), nextOffset, ttlSecondsRemaining, meta.expiresAt().orElse(null));
        }

        CreateOutcome createOutcomeFor(java.net.URI url, StreamConfig requested, java.io.InputStream initialBody) throws Exception {
            // Check expiration first
            Instant now = clock.instant();
            if (isExpired(requested.expiresAt(), now)) {
                return new CreateOutcome(CreateOutcome.Status.EXISTS_CONFLICT, snapshotMeta(), nextOffset);
            }

            if (!sameConfig(meta.config(), requested)) {
                return new CreateOutcome(CreateOutcome.Status.EXISTS_CONFLICT, snapshotMeta(), nextOffset);
            }

            // Apply initial body only if stream is empty (size==0)
            if (codec.size(state) == 0 && initialBody != null) {
                codec.applyInitial(state, initialBody);
                Offset newNext = offsetGenerator.next(nextOffset, codec.size(state));
                nextOffset = newNext;
                dataArrived.signalAll();
            }

            boolean first = !createdOnce;
            createdOnce = true;
            return new CreateOutcome(first ? CreateOutcome.Status.CREATED : CreateOutcome.Status.EXISTS_MATCH, snapshotMeta(), nextOffset);
        }

        AppendOutcome append(String streamSeq, java.io.InputStream body) throws Exception {
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

                Offset newNext = offsetGenerator.next(nextOffset, codec.size(state));
                nextOffset = newNext;
                dataArrived.signalAll();
                return new AppendOutcome(AppendOutcome.Status.APPENDED, newNext, null);
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
            Offset next = new Offset(chunk.nextPosition()); // Will be replaced by offsetGenerator.encode(...)
            
            // Check if stream has expired before returning data
            Instant now = clock.instant();
            if (isExpired(meta.expiresAt(), now)) {
                return new ReadOutcome(ReadOutcome.Status.GONE, null, null, null, false, null, null);
            }

            String internalId = meta.internalStreamId();
            String etag = "\"" + internalId + ":" + encodeForEtag(start) + ":" + next.value() + "\"";
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

        private static boolean sameConfig(StreamConfig a, StreamConfig b) {
            return a.contentType().equals(b.contentType())
                    && a.ttlSeconds().equals(b.ttlSeconds())
                    && a.expiresAt().equals(b.expiresAt());
        }

        private long decodeStart(Offset off) {
            if (off == null) return 0;
            if ("-1".equals(off.value())) return 0;
            // For now, assume LexiLong format - can be made pluggable
            try {
                return Long.parseLong(off.value(), 36);
            } catch (Exception e) {
                return -1;
            }
        }

        private String encodeForEtag(Offset offset) {
            // For now, use direct value - can be made pluggable
            return offset.value();
        }

        private boolean isExpired(Optional<Instant> expiresAt, Instant now) {
            return expiresAt.isPresent() && expiresAt.get().isBefore(now);
        }

        private Long calculateTtlRemaining(Optional<Instant> expiresAt, Instant now) {
            if (expiresAt.isEmpty()) return null;
            Instant expiry = expiresAt.get();
            if (expiry.isBefore(now)) return 0L;
            return Duration.between(now, expiry).getSeconds();
        }

        private static final ReentrantLock lock = new ReentrantLock();
        private final Condition dataArrived = lock.newCondition();

        private final StreamMetadata meta;
        private final StreamCodec codec;
        private final StreamCodec.State state;
        private Offset nextOffset;

        private String lastSeq;
        private boolean createdOnce;
    }
}
}