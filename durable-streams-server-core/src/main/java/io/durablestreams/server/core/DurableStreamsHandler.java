package io.durablestreams.server.core;

import io.durablestreams.core.Headers;
import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.server.spi.*;

import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Framework-neutral HTTP handler implementing the Durable Streams Protocol.
 *
 * <p>This handler delegates persistence and policy decisions to {@link StreamStore}, {@link CursorPolicy},
 * and {@link CachePolicy}.
 *
 * <p>Use {@link #builder(StreamStore)} to create instances with custom configuration:
 * <pre>{@code
 * DurableStreamsHandler handler = DurableStreamsHandler.builder(store)
 *     .longPollTimeout(Duration.ofSeconds(30))
 *     .maxBodySize(5 * 1024 * 1024)  // 5 MB
 *     .rateLimiter(myRateLimiter)
 *     .build();
 * }</pre>
 */
public final class DurableStreamsHandler {

    /**
     * Convenience constant: 10 MB.
     *
     * <p>Not used by default.
     */
    @SuppressWarnings("unused")
    public static final long DEFAULT_MAX_BODY_SIZE = 10 * 1024 * 1024;

    /**
     * Disable body size limiting (unlimited).
     *
     * <p>This is the default; let the hosting framework enforce limits.
     */
    public static final long NO_BODY_SIZE_LIMIT = Long.MAX_VALUE;

    private final StreamStore store;
    private final CursorPolicy cursorPolicy;
    private final CachePolicy cachePolicy;
    private final Duration longPollTimeout;
    private final Duration sseMaxDuration;
    private final int maxChunkSize; // bytes for byte streams; messages for JSON streams
    private final Clock clock;
    private final RateLimiter rateLimiter;
    private final long maxBodySize;

    /**
     * Creates a new builder for configuring a handler.
     *
     * @param store the stream store (required)
     * @return a new builder instance
     */
    public static Builder builder(StreamStore store) {
        return new Builder(store);
    }

    public DurableStreamsHandler(StreamStore store) {
        this(builder(store));
    }

    private DurableStreamsHandler(Builder builder) {
        this.store = Objects.requireNonNull(builder.store, "store");
        this.cursorPolicy = builder.cursorPolicy != null ? builder.cursorPolicy : new CursorPolicy(builder.clock);
        this.cachePolicy = builder.cachePolicy != null ? builder.cachePolicy : CachePolicy.defaultPrivate();
        this.longPollTimeout = builder.longPollTimeout != null ? builder.longPollTimeout : Duration.ofSeconds(25);
        this.sseMaxDuration = builder.sseMaxDuration != null ? builder.sseMaxDuration : Duration.ofSeconds(55);
        this.maxChunkSize = builder.maxChunkSize > 0 ? builder.maxChunkSize : 64 * 1024;
        this.clock = builder.clock != null ? builder.clock : Clock.systemUTC();
        this.rateLimiter = builder.rateLimiter != null ? builder.rateLimiter : RateLimiter.permitAll();
        this.maxBodySize = builder.maxBodySize > 0 ? builder.maxBodySize : NO_BODY_SIZE_LIMIT;
    }

    /**
     * Builder for {@link DurableStreamsHandler}.
     */
    public static final class Builder {
        private final StreamStore store;
        private CursorPolicy cursorPolicy;
        private CachePolicy cachePolicy;
        private Duration longPollTimeout;
        private Duration sseMaxDuration;
        private int maxChunkSize;
        private Clock clock = Clock.systemUTC();
        private RateLimiter rateLimiter;
        private long maxBodySize;

        private Builder(StreamStore store) {
            this.store = Objects.requireNonNull(store, "store");
        }

        /** Sets the cursor policy for CDN cache collapsing prevention. */
        public Builder cursorPolicy(CursorPolicy cursorPolicy) {
            this.cursorPolicy = cursorPolicy;
            return this;
        }

        /** Sets the cache control policy for GET responses. */
        public Builder cachePolicy(CachePolicy cachePolicy) {
            this.cachePolicy = cachePolicy;
            return this;
        }

        /** Sets the long-poll timeout duration. Default: 25 seconds. */
        public Builder longPollTimeout(Duration longPollTimeout) {
            this.longPollTimeout = longPollTimeout;
            return this;
        }

        /** Sets the maximum SSE connection duration. Default: 55 seconds. */
        public Builder sseMaxDuration(Duration sseMaxDuration) {
            this.sseMaxDuration = sseMaxDuration;
            return this;
        }

        /** Sets the maximum chunk size for reads (bytes for byte streams, messages for JSON). Default: 64KB. */
        public Builder maxChunkSize(int maxChunkSize) {
            this.maxChunkSize = maxChunkSize;
            return this;
        }

        /** Sets the clock for time-based operations. Default: system UTC. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Sets the rate limiter. Default: {@link RateLimiter#permitAll()} (disabled).
         *
         * <p>Use {@link RateLimiter#permitAll()} when the framework handles rate limiting externally.
         */
        public Builder rateLimiter(RateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
            return this;
        }

        /**
         * Sets the maximum request body size in bytes. Default: unlimited.
         *
         * <p>Use {@link DurableStreamsHandler#DEFAULT_MAX_BODY_SIZE} as a conservative default, or
         * {@link DurableStreamsHandler#NO_BODY_SIZE_LIMIT} to disable limiting.
         */
        public Builder maxBodySize(long maxBodySize) {
            this.maxBodySize = maxBodySize;
            return this;
        }

        /** Builds the handler with the configured settings. */
        public DurableStreamsHandler build() {
            return new DurableStreamsHandler(this);
        }
    }

    public ServerResponse handle(ServerRequest req) {
        return handle(req, null);
    }

    /**
     * Handle a request with optional client identifier for rate limiting.
     *
     * @param req the incoming request
     * @param clientId optional client identifier (e.g., IP address, API key) for rate limiting
     * @return the response
     */
    public ServerResponse handle(ServerRequest req, String clientId) {
        try {
            // Check rate limiting first
            URI url = stripQuery(req.uri());
            RateLimiter.Result rateResult = rateLimiter.tryAcquire(url, clientId);
            if (rateResult instanceof RateLimiter.Result.Rejected rejected) {
                ServerResponse resp = new ServerResponse(429, new ResponseBody.Empty())
                        .header("Cache-Control", "no-store")
                        .header("X-Error", "rate_limit_exceeded");
                rejected.retryAfter().ifPresent(d ->
                        resp.header("Retry-After", Long.toString(d.getSeconds())));
                return resp;
            }

            return switch (req.method()) {
                case PUT -> handleCreate(req);
                case POST -> handleAppend(req);
                case DELETE -> handleDelete(req);
                case HEAD -> handleHead(req);
                case GET -> handleGet(req);
            };
        } catch (BadRequest | IllegalArgumentException br) {
            return new ServerResponse(400, new ResponseBody.Empty())
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .header("Cache-Control", "no-store")
                    .header("X-Error", br.getMessage());
        } catch (BodySizeLimiter.PayloadTooLargeException ptle) {
            return new ServerResponse(413, new ResponseBody.Empty())
                    .header("Cache-Control", "no-store")
                    .header("X-Error", "payload_too_large")
                    .header("X-Max-Size", Long.toString(ptle.maxBytes()));
        } catch (Exception e) {
            return new ServerResponse(500, new ResponseBody.Empty())
                    .header("Cache-Control", "no-store")
                    .header("X-Error", "internal_error");
        }
    }

    private ServerResponse handleCreate(ServerRequest req) throws Exception {
        URI url = stripQuery(req.uri());
        String contentType = firstHeader(req, Protocol.H_CONTENT_TYPE).orElse("application/octet-stream");

        Long ttl = parseTtlSeconds(firstHeader(req, Protocol.H_STREAM_TTL).orElse(null));
        Instant expiresAt = parseRfc3339(firstHeader(req, Protocol.H_STREAM_EXPIRES_AT).orElse(null));
        if (ttl != null && expiresAt != null) throw new BadRequest("Stream-TTL and Stream-Expires-At both set");

        StreamConfig config = new StreamConfig(contentType, ttl, expiresAt);

        // Wrap body with size limiter to enforce 413 Payload Too Large
        InputStream body = BodySizeLimiter.limit(req.body(), maxBodySize);
        CreateOutcome out;
        try {
            out = store.create(url, config, body);
        } catch (IllegalArgumentException e) {
            throw new BadRequest(e.getMessage());
        }


        if (out.status() == CreateOutcome.Status.EXISTS_CONFLICT) {
            return new ServerResponse(409, new ResponseBody.Empty()).header("Cache-Control", "no-store");
        }

        // determine status code: CREATED vs idempotent
        int status = (out.status() == CreateOutcome.Status.CREATED) ? 201 : 200;
        ServerResponse resp = new ServerResponse(status, new ResponseBody.Empty())
                .header(Protocol.H_CONTENT_TYPE, out.metadata().config().contentType())
                .header(Protocol.H_STREAM_NEXT_OFFSET, out.nextOffset().value())
                .header("Cache-Control", "no-store"); // create responses are not cacheable

        if (status == 201) {
            resp.header("Location", url.toString());
        }
        return resp;
    }

    private ServerResponse handleAppend(ServerRequest req) throws Exception {
        URI url = stripQuery(req.uri());
        String contentType = firstHeader(req, Protocol.H_CONTENT_TYPE).orElse(null);
        if (contentType == null) throw new BadRequest("missing Content-Type");
        String streamSeq = firstHeader(req, Protocol.H_STREAM_SEQ).orElse(null);

        if (req.body() == null) throw new BadRequest("empty body");
        InputStream body = BodySizeLimiter.limit(req.body(), maxBodySize);
        AppendOutcome out = store.append(url, contentType, streamSeq, body);

        return switch (out.status()) {
            case NOT_FOUND -> new ServerResponse(404, new ResponseBody.Empty()).header("Cache-Control", "no-store");
            case NOT_SUPPORTED -> new ServerResponse(501, new ResponseBody.Empty()).header("Cache-Control", "no-store");
            case CONFLICT -> new ServerResponse(409, new ResponseBody.Empty()).header("Cache-Control", "no-store");
            case BAD_REQUEST -> new ServerResponse(400, new ResponseBody.Empty()).header("Cache-Control", "no-store");
            case APPENDED -> new ServerResponse(204, new ResponseBody.Empty())
                    .header(Protocol.H_STREAM_NEXT_OFFSET, out.nextOffset().value())
                    .header("Cache-Control", "no-store");
        };
    }

    private ServerResponse handleDelete(ServerRequest req) throws Exception {
        URI url = stripQuery(req.uri());
        boolean ok = store.delete(url);
        if (!ok) return new ServerResponse(404, new ResponseBody.Empty()).header("Cache-Control", "no-store");
        return new ServerResponse(204, new ResponseBody.Empty()).header("Cache-Control", "no-store");
    }

    private ServerResponse handleHead(ServerRequest req) throws Exception {
        URI url = stripQuery(req.uri());
        Optional<StreamMetadata> meta = store.head(url);
        if (meta.isEmpty()) return new ServerResponse(404, new ResponseBody.Empty()).header("Cache-Control", "no-store");

        StreamMetadata m = meta.get();
        ServerResponse resp = new ServerResponse(200, new ResponseBody.Empty())
                .header(Protocol.H_CONTENT_TYPE, m.config().contentType())
                .header(Protocol.H_STREAM_NEXT_OFFSET, m.nextOffset().value())
                .header("Cache-Control", "no-store");

        m.ttlSecondsRemaining().ifPresent(v -> resp.header(Protocol.H_STREAM_TTL, Long.toString(v)));
        m.expiresAt().ifPresent(v -> resp.header(Protocol.H_STREAM_EXPIRES_AT, v.toString()));
        return resp;
    }

    private ServerResponse handleGet(ServerRequest req) throws Exception {
        URI url = stripQuery(req.uri());
        if (QueryString.hasDuplicate(req.uri(), Protocol.Q_OFFSET)) {
            throw new BadRequest("duplicate offset parameter");
        }
        Map<String, String> q = QueryString.parse(req.uri());
        String live = q.get(Protocol.Q_LIVE);

        if (live == null || live.isEmpty()) {
            return handleCatchUp(req, url, q);
        }

        if (Protocol.LIVE_LONG_POLL.equals(live)) {
            return handleLongPoll(req, url, q);
        }

        if (Protocol.LIVE_SSE.equals(live)) {
            return handleSse(req, url, q);
        }

        throw new BadRequest("invalid live mode");
    }

    private ServerResponse handleCatchUp(ServerRequest req, URI url, Map<String, String> q) throws Exception {
        Offset offset = parseOffset(q.get(Protocol.Q_OFFSET));
        ReadOutcome out = store.read(url, offset, maxChunkSize);
        return mapReadOutcome(out, req, url, /*isLive*/ false, /*clientCursor*/ null);
    }

    private ServerResponse handleLongPoll(ServerRequest req, URI url, Map<String, String> q) throws Exception {
        String off = q.get(Protocol.Q_OFFSET);
        if (off == null) throw new BadRequest("offset required for long-poll");
        Offset offset = parseOffset(off);

        String clientCursor = q.get(Protocol.Q_CURSOR);

        ReadOutcome out = store.read(url, offset, maxChunkSize);
        if (out.status() != ReadOutcome.Status.OK) {
            return mapReadOutcome(out, req, url, true, clientCursor);
        }

        boolean hasBody = out.body() != null && out.body().length > 0;
        if (!hasBody && out.upToDate()) {
            boolean ready = store.await(url, offset, longPollTimeout);
            if (!ready) {
                // 204 with tail nextOffset + up-to-date true
                Optional<StreamMetadata> meta = store.head(url);
                if (meta.isEmpty()) return new ServerResponse(404, new ResponseBody.Empty()).header("Cache-Control", "no-store");

                String cursor = cursorPolicy.nextCursor(clientCursor);
                return new ServerResponse(204, new ResponseBody.Empty())
                        .header(Protocol.H_STREAM_NEXT_OFFSET, meta.get().nextOffset().value())
                        .header(Protocol.H_STREAM_UP_TO_DATE, Protocol.BOOL_TRUE)
                        .header(Protocol.H_STREAM_CURSOR, cursor)
                        .header("Cache-Control", "no-store");
            }
            out = store.read(url, offset, maxChunkSize);
        }

        return mapReadOutcome(out, req, url, true, clientCursor);
    }

    private ServerResponse handleSse(@SuppressWarnings("unused") ServerRequest req, URI url, Map<String, String> q) throws Exception {
        String off = q.get(Protocol.Q_OFFSET);
        if (off == null) throw new BadRequest("offset required for sse");
        Offset offset = parseOffset(off);

        Optional<StreamMetadata> meta = store.head(url);
        if (meta.isEmpty()) return new ServerResponse(404, new ResponseBody.Empty()).header("Cache-Control", "no-store");
        String ct = meta.get().config().contentType();
        if (!isSseCompatible(ct)) throw new BadRequest("content type incompatible with SSE");

        String clientCursor = q.get(Protocol.Q_CURSOR);
        FlowingSsePublisher pub = new FlowingSsePublisher(store, cursorPolicy, url, offset, clientCursor, maxChunkSize, sseMaxDuration, clock);
        return new ServerResponse(200, new ResponseBody.Sse(pub))
                .header(Protocol.H_CONTENT_TYPE, Protocol.CT_EVENT_STREAM)
                .header("Cache-Control", "no-cache");
    }

    private ServerResponse mapReadOutcome(ReadOutcome out, ServerRequest req, URI url, boolean isLive, String clientCursor) throws Exception {
        return switch (out.status()) {
            case NOT_FOUND -> new ServerResponse(404, new ResponseBody.Empty()).header("Cache-Control", "no-store");
            case GONE -> new ServerResponse(410, new ResponseBody.Empty()).header("Cache-Control", "no-store");
            case BAD_REQUEST -> new ServerResponse(400, new ResponseBody.Empty()).header("Cache-Control", "no-store");
            case OK -> {
                // ETag/If-None-Match handling (protocol MUST support 304 when matches)
                String etag = out.etag();
                String inm = firstHeader(req, Protocol.H_IF_NONE_MATCH).orElse(null);
                if (inm != null && inm.equals(etag)) {
                    yield new ServerResponse(304, new ResponseBody.Empty())
                            .header("Cache-Control", "no-store")
                            .header(Protocol.H_ETAG, etag)
                            .header(Protocol.H_STREAM_NEXT_OFFSET, out.nextOffset().value());
                }

                ServerResponse r;
                if (out.fileRegion().isPresent()) {
                    ResponseBody.FileRegion region = new ResponseBody.FileRegion(out.fileRegion().get());
                    r = new ServerResponse(200, region)
                            .header(Protocol.H_CONTENT_TYPE, out.contentType() == null ? "application/octet-stream" : out.contentType())
                            .header(Protocol.H_STREAM_NEXT_OFFSET, out.nextOffset().value());
                } else {
                    byte[] body = out.body() == null ? new byte[0] : out.body();
                    r = new ServerResponse(200, new ResponseBody.Bytes(body))
                            .header(Protocol.H_CONTENT_TYPE, out.contentType() == null ? "application/octet-stream" : out.contentType())
                            .header(Protocol.H_STREAM_NEXT_OFFSET, out.nextOffset().value());
                }


                if (etag != null) r.header(Protocol.H_ETAG, etag);

                // Stream-Up-To-Date: true MUST when up-to-date
                if (out.upToDate()) r.header(Protocol.H_STREAM_UP_TO_DATE, Protocol.BOOL_TRUE);

                // Cache-Control per policy for catch-up and 200 long-poll
                Optional<StreamMetadata> meta = store.head(url);
                if (meta.isPresent() && !isLive) {
                    r.header("Cache-Control", cachePolicy.cacheControlFor(meta.get()));
                } else {
                    r.header("Cache-Control", "no-store");
                }

                // Cursor: optional on catch-up, MUST on live responses
                if (isLive) {
                    String cursor = cursorPolicy.nextCursor(clientCursor);
                    r.header(Protocol.H_STREAM_CURSOR, cursor);
                }
                yield r;
            }
        };
    }

    private static boolean isSseCompatible(String ct) {
        String normalized = normalizeContentType(ct);
        return normalized.startsWith("text/") || normalized.equals("application/json");
    }

    private static Offset parseOffset(String raw) {
        if (raw == null) return Offset.beginning();
        if (raw.isEmpty()) throw new BadRequest("offset must not be empty");
        if (Protocol.OFFSET_BEGINNING.equals(raw)) return Offset.beginning();
        try {
            return new Offset(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequest(e.getMessage());
        }
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static Optional<String> firstHeader(ServerRequest req, String name) {
        return Headers.firstValue(req.headers(), name);
    }

    private static URI stripQuery(URI uri) {
        // protocol treats stream as URL; operations are applied to stream URL (without query)
        return URI.create(uri.getScheme() + "://" + uri.getAuthority() + uri.getPath());
    }

    private static Long parseTtlSeconds(String v) {
        if (v == null) return null;
        if (v.isEmpty()) throw new BadRequest("empty Stream-TTL");
        if (v.startsWith("+") || v.startsWith("-")) throw new BadRequest("Stream-TTL must be non-negative integer");
        if (v.length() > 1 && v.startsWith("0")) throw new BadRequest("Stream-TTL must not have leading zeros");
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) throw new BadRequest("invalid Stream-TTL");
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new BadRequest("invalid Stream-TTL");
        }
    }

    private static Instant parseRfc3339(String v) {
        if (v == null) return null;
        try { return Instant.parse(v); }
        catch (Exception e) { throw new BadRequest("invalid Stream-Expires-At"); }
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String msg) { super(msg); }
    }
}
