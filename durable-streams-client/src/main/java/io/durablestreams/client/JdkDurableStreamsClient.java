package io.durablestreams.client;

import io.durablestreams.core.Headers;
import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;

public final class JdkDurableStreamsClient implements DurableStreamsClient {

    private final DurableStreamsTransport transport;

    public JdkDurableStreamsClient(HttpClient http) {
        this(new JdkHttpTransport(http));
    }

    public JdkDurableStreamsClient(DurableStreamsTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public CreateResult create(CreateRequest request) throws Exception {
        byte[] body = readAllBytesOrNull(request.initialBody());
        Map<String, Iterable<String>> headers = withContentType(request.contentType(), request.headers());
        TransportRequest req = new TransportRequest("PUT", request.streamUrl(), headers, body, null);
        TransportResponse<Void> resp = transport.sendDiscarding(req);
        Offset next = headerOffset(resp.headers(), Protocol.H_STREAM_NEXT_OFFSET);
        return new CreateResult(resp.status(), next);
    }

    @Override
    public AppendResult append(AppendRequest request) throws Exception {
        byte[] body = readAllBytesOrNull(request.body());
        Map<String, Iterable<String>> headers = withContentType(request.contentType(), request.headers());
        TransportRequest req = new TransportRequest("POST", request.streamUrl(), headers, body, null);
        TransportResponse<Void> resp = transport.sendDiscarding(req);
        Offset next = headerOffset(resp.headers(), Protocol.H_STREAM_NEXT_OFFSET);
        return new AppendResult(resp.status(), next);
    }

    @Override
    public HeadResult head(URI streamUrl) throws Exception {
        TransportRequest req = new TransportRequest("HEAD", streamUrl, Map.of(), null, null);
        TransportResponse<Void> resp = transport.sendDiscarding(req);
        String contentType = first(resp.headers(), Protocol.H_CONTENT_TYPE).orElse(null);
        Offset next = headerOffset(resp.headers(), Protocol.H_STREAM_NEXT_OFFSET);
        Long ttl = parseLongSafe(first(resp.headers(), Protocol.H_STREAM_TTL).orElse(null));
        Instant expiresAt = parseInstantSafe(first(resp.headers(), Protocol.H_STREAM_EXPIRES_AT).orElse(null));
        return new HeadResult(resp.status(), contentType, next, ttl, expiresAt);
    }

    @Override
    public void delete(URI streamUrl) throws Exception {
        TransportRequest req = new TransportRequest("DELETE", streamUrl, Map.of(), null, null);
        transport.sendDiscarding(req);
    }

    @Override
    public ReadResult readCatchUp(ReadRequest request) throws Exception {
        Offset offset = request.offset() == null ? Offset.beginning() : request.offset();
        URI url = Urls.withQuery(request.streamUrl(), Map.of(Protocol.Q_OFFSET, offset.value()));
        Map<String, Iterable<String>> headers = new LinkedHashMap<>();
        if (request.ifNoneMatch() != null) {
            headers.put(Protocol.H_IF_NONE_MATCH, List.of(request.ifNoneMatch()));
        }

        TransportRequest req = new TransportRequest("GET", url, headers, null, null);
        TransportResponse<byte[]> resp = transport.sendBytes(req);
        Offset next = headerOffset(resp.headers(), Protocol.H_STREAM_NEXT_OFFSET);
        String ct = first(resp.headers(), Protocol.H_CONTENT_TYPE).orElse(null);
        boolean upToDate = Protocol.BOOL_TRUE.equalsIgnoreCase(first(resp.headers(), Protocol.H_STREAM_UP_TO_DATE).orElse("false"));
        String etag = first(resp.headers(), Protocol.H_ETAG).orElse(null);
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        return new ReadResult(resp.status(), body, ct, next, upToDate, etag);
    }

    @Override
    public Flow.Publisher<StreamEvent> subscribeLongPoll(LiveLongPollRequest request) {
        return new LongPollLoop(transport, request).publisher();
    }

    @Override
    public Flow.Publisher<StreamEvent> subscribeSse(LiveSseRequest request) {
        return new SseLoop(transport, request).publisher();
    }

    private static Map<String, Iterable<String>> withContentType(String contentType, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of(Protocol.H_CONTENT_TYPE, List.of(contentType));
        }
        Map<String, Iterable<String>> out = new LinkedHashMap<>();
        out.put(Protocol.H_CONTENT_TYPE, List.of(contentType));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey(), List.of(e.getValue()));
            }
        }
        return out;
    }

    private static Optional<String> first(Map<String, ? extends Iterable<String>> headers, String name) {
        return Headers.firstValue(headers, name);
    }

    private static Offset headerOffset(Map<String, ? extends Iterable<String>> headers, String name) {
        String v = first(headers, name).orElse(null);
        return v == null ? null : new Offset(v);
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

    private static byte[] readAllBytesOrNull(InputStream in) throws Exception {
        if (in == null) {
            return null;
        }
        return in.readAllBytes();
    }
}
