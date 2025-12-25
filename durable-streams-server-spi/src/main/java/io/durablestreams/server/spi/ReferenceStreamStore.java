package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class ReferenceStreamStore implements StreamStore {
    private static final int DEFAULT_MAX_BYTES = 64 * 1024;
    private static final int DEFAULT_MAX_MESSAGES = 1024;

    private final OffsetGenerator offsetGenerator;
    private final StreamCodecRegistry codecRegistry;
    private final Clock clock;
    private final Map<URI, StreamState> streams = new ConcurrentHashMap<>();

    public ReferenceStreamStore(OffsetGenerator offsetGenerator) {
        this(offsetGenerator, defaultRegistry(), Clock.systemUTC());
    }

    public ReferenceStreamStore(OffsetGenerator offsetGenerator, StreamCodecRegistry codecRegistry) {
        this(offsetGenerator, codecRegistry, Clock.systemUTC());
    }

    public ReferenceStreamStore(OffsetGenerator offsetGenerator, StreamCodecRegistry codecRegistry, Clock clock) {
        this.offsetGenerator = Objects.requireNonNull(offsetGenerator, "offsetGenerator");
        this.codecRegistry = Objects.requireNonNull(codecRegistry, "codecRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CreateOutcome create(URI url, StreamConfig config, InputStream initialBody) throws Exception {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(config, "config");

        StreamState s = streams.compute(url, (u, existing) -> {
            if (existing != null) return existing;
            return StreamState.createNew(config, codecRegistry, offsetGenerator, clock);
        });

        return s.createOutcomeFor(config, initialBody);
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

    private static StreamCodecRegistry defaultRegistry() {
        List<StreamCodec> codecs = new ArrayList<>();
        ServiceLoader<StreamCodecProvider> loader = ServiceLoader.load(StreamCodecProvider.class);
        for (StreamCodecProvider provider : loader) {
            codecs.addAll(provider.codecs());
        }
        return contentType -> codecs.stream()
                .filter(codec -> codec.contentType().equalsIgnoreCase(contentType))
                .findFirst();
    }

    private static final class StreamState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition dataArrived = lock.newCondition();

        private final StreamMetadata meta;
        private final StreamCodec codec;
        private final StreamCodec.State state;
        private final OffsetGenerator offsetGenerator;
        private final Clock clock;
        private Offset nextOffset;
        private String lastSeq;
        private boolean createdOnce;

        private StreamState(
                StreamMetadata meta,
                StreamCodec codec,
                StreamCodec.State state,
                Offset nextOffset,
                OffsetGenerator offsetGenerator,
                Clock clock
        ) {
            this.meta = meta;
            this.codec = codec;
            this.state = state;
            this.nextOffset = nextOffset;
            this.offsetGenerator = offsetGenerator;
            this.clock = clock;
        }

        static StreamState createNew(
                StreamConfig config,
                StreamCodecRegistry codecRegistry,
                OffsetGenerator offsetGenerator,
                Clock clock
        ) {
            String ct = config.contentType();
            StreamCodec codec;
            if ("application/json".equalsIgnoreCase(ct)) {
                codec = codecRegistry.find(ct).orElseThrow(() ->
                        new IllegalArgumentException("application/json requires an installed JSON codec module"));
            } else {
                codec = codecRegistry.find(ct).orElseGet(ByteStreamCodec::new);
            }
            StreamCodec.State st = codec.createEmpty();
            Offset next = Offset.beginning();
            StreamMetadata meta = new StreamMetadata(UUID.randomUUID().toString().replace("-", ""), config, next, null, config.expiresAt().orElse(null));
            return new StreamState(meta, codec, st, next, offsetGenerator, clock);
        }

        boolean isJson() {
            return "application/json".equalsIgnoreCase(meta.config().contentType());
        }

        StreamMetadata snapshotMeta() {
            Long ttlSecondsRemaining = calculateTtlRemaining(meta.expiresAt(), clock.instant());
            return new StreamMetadata(meta.internalStreamId(), meta.config(), nextOffset, ttlSecondsRemaining, meta.expiresAt().orElse(null));
        }

        CreateOutcome createOutcomeFor(StreamConfig requested, InputStream initialBody) throws Exception {
            if (!sameConfig(meta.config(), requested)) {
                return new CreateOutcome(CreateOutcome.Status.EXISTS_CONFLICT, snapshotMeta(), nextOffset);
            }

            if (codec.size(state) == 0 && initialBody != null) {
                codec.applyInitial(state, initialBody);
                nextOffset = offsetGenerator.next(nextOffset, codec.size(state));
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

                nextOffset = offsetGenerator.next(nextOffset, codec.size(state));
                dataArrived.signalAll();
                return new AppendOutcome(AppendOutcome.Status.APPENDED, nextOffset, null);
            } finally {
                lock.unlock();
            }
        }

        ReadOutcome read(Offset startOffset, int limit) throws Exception {
            if (isExpired(meta.expiresAt(), clock.instant())) {
                return new ReadOutcome(ReadOutcome.Status.GONE, null, null, null, false, null, null);
            }

            long pos = decodeStart(startOffset);
            if (pos < 0) return new ReadOutcome(ReadOutcome.Status.BAD_REQUEST, null, null, null, false, null, null);

            long tail = codec.size(state);
            long start = Math.min(pos, tail);

            StreamCodec.ReadChunk chunk = codec.read(state, start, limit);
            Offset next = offsetGenerator.next(nextOffset, chunk.nextPosition());
            Offset startOffsetForEtag = offsetGenerator.next(nextOffset, start);
            String etag = "\"" + meta.internalStreamId() + ":" + startOffsetForEtag.value() + ":" + next.value() + "\"";

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

        private static long decodeStart(Offset off) {
            if (off == null) return 0;
            if ("-1".equals(off.value())) return 0;
            try {
                return Long.parseLong(off.value(), 36);
            } catch (Exception e) {
                return -1;
            }
        }

        private static boolean isExpired(Optional<Instant> expiresAt, Instant now) {
            return expiresAt.isPresent() && expiresAt.get().isBefore(now);
        }

        private static Long calculateTtlRemaining(Optional<Instant> expiresAt, Instant now) {
            if (expiresAt.isEmpty()) return null;
            Instant expiry = expiresAt.get();
            if (expiry.isBefore(now)) return 0L;
            return Duration.between(now, expiry).getSeconds();
        }
    }

    private static final class ByteStreamCodec implements StreamCodec {
        @Override
        public String contentType() {
            return "application/octet-stream";
        }

        @Override
        public State createEmpty() {
            return new ByteState();
        }

        @Override
        public void applyInitial(State state, InputStream body) throws Exception {
            if (body == null) return;
            byte[] bytes = readAll(body);
            if (bytes.length == 0) return;
            ((ByteState) state).append(bytes);
        }

        @Override
        public void append(State state, InputStream body) throws Exception {
            byte[] bytes = readAll(body);
            if (bytes.length == 0) throw new IllegalArgumentException("empty body");
            ((ByteState) state).append(bytes);
        }

        @Override
        public ReadChunk read(State state, long start, int limit) {
            ByteState st = (ByteState) state;
            byte[] all = st.bytes();
            if (start >= all.length) {
                return new ReadChunk(new byte[0], all.length, true);
            }
            int end = (int) Math.min(all.length, start + limit);
            byte[] chunk = Arrays.copyOfRange(all, (int) start, end);
            return new ReadChunk(chunk, end, end >= all.length);
        }

        @Override
        public long size(State state) {
            return ((ByteState) state).size();
        }

        private static byte[] readAll(InputStream body) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = body.read(buf)) >= 0) out.write(buf, 0, r);
            return out.toByteArray();
        }

        private static final class ByteState implements State {
            private final ByteArrayOutputStream out = new ByteArrayOutputStream();

            private void append(byte[] bytes) throws Exception {
                out.write(bytes);
            }

            private long size() {
                return out.size();
            }

            private byte[] bytes() {
                return out.toByteArray();
            }
        }
    }
}
