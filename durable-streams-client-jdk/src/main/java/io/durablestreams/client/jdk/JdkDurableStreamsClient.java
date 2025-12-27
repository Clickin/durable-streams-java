package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;
import io.durablestreams.http.spi.HttpClientAdapter;
import io.durablestreams.http.spi.HttpClientRequest;
import io.durablestreams.http.spi.HttpClientResponse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Flow;

public final class JdkDurableStreamsClient implements DurableStreamsClient {

    private final HttpClientAdapter http;

    public JdkDurableStreamsClient(HttpClientAdapter http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public CreateResult create(CreateRequest request) throws Exception {
        byte[] body = readAllBytes(request.initialBody());
        HttpClientRequest.Builder b = HttpClientRequest.put(request.streamUrl())
                .body(body)
                .header(Protocol.H_CONTENT_TYPE, request.contentType());

        applyHeaders(b, request.headers());
        HttpClientResponse resp = http.send(b.build());
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        return new CreateResult(resp.statusCode(), next);
    }

    @Override
    public AppendResult append(AppendRequest request) throws Exception {
        byte[] body = readAllBytes(request.body());
        HttpClientRequest.Builder b = HttpClientRequest.post(request.streamUrl())
                .body(body)
                .header(Protocol.H_CONTENT_TYPE, request.contentType());

        applyHeaders(b, request.headers());
        HttpClientResponse resp = http.send(b.build());
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        return new AppendResult(resp.statusCode(), next);
    }

    @Override
    public HeadResult head(URI streamUrl) throws Exception {
        HttpClientRequest req = HttpClientRequest.head(streamUrl).build();

        HttpClientResponse resp = http.send(req);
        String contentType = resp.header(Protocol.H_CONTENT_TYPE).orElse(null);
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        Long ttl = parseLongSafe(resp.header(Protocol.H_STREAM_TTL).orElse(null));
        Instant expiresAt = parseInstantSafe(resp.header(Protocol.H_STREAM_EXPIRES_AT).orElse(null));
        return new HeadResult(resp.statusCode(), contentType, next, ttl, expiresAt);
    }

    @Override
    public void delete(URI streamUrl) throws Exception {
        HttpClientRequest req = HttpClientRequest.delete(streamUrl).build();
        http.send(req);
    }

    @Override
    public ReadResult readCatchUp(ReadRequest request) throws Exception {
        Offset offset = request.offset() == null ? Offset.beginning() : request.offset();
        URI url = Urls.withQuery(request.streamUrl(), Map.of(Protocol.Q_OFFSET, offset.value()));
        HttpClientRequest.Builder b = HttpClientRequest.get(url);
        if (request.ifNoneMatch() != null) {
            b.header(Protocol.H_IF_NONE_MATCH, request.ifNoneMatch());
        }

        HttpClientResponse resp = http.send(b.build());
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        String ct = resp.header(Protocol.H_CONTENT_TYPE).orElse(null);
        boolean upToDate = Protocol.BOOL_TRUE.equalsIgnoreCase(resp.header(Protocol.H_STREAM_UP_TO_DATE).orElse("false"));
        String etag = resp.header(Protocol.H_ETAG).orElse(null);
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        return new ReadResult(resp.statusCode(), body, ct, next, upToDate, etag);
    }

    @Override
    public Flow.Publisher<StreamEvent> subscribeLongPoll(LiveLongPollRequest request) {
        return new LongPollLoop(http, request).publisher();
    }

    @Override
    public Flow.Publisher<StreamEvent> subscribeSse(LiveSseRequest request) {
        return new SseLoop(http, request).publisher();
    }

    private static void applyHeaders(HttpClientRequest.Builder b, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) b.header(e.getKey(), e.getValue());
        }
    }

    private static Offset headerOffset(HttpClientResponse resp, String name) {
        String v = resp.header(name).orElse(null);
        if (v == null) return null;
        return new Offset(v);
    }

    private static Long parseLongSafe(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseInstantSafe(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Instant.parse(v.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
        return out.toByteArray();
    }
}