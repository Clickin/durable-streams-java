package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;

public final class JdkDurableStreamsClient implements DurableStreamsClient {

    private static final Duration DEFAULT_LONG_POLL_TIMEOUT = Duration.ofSeconds(25);
    private final HttpClient http;

    public JdkDurableStreamsClient(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public CreateResult create(CreateRequest request) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(request.streamUrl())
                .PUT(bodyPublisher(request.initialBody()))
                .header(Protocol.H_CONTENT_TYPE, request.contentType());

        applyHeaders(b, request.headers());
        HttpResponse<Void> resp = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        return new CreateResult(resp.statusCode(), next);
    }

    @Override
    public AppendResult append(AppendRequest request) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(request.streamUrl())
                .POST(bodyPublisher(request.body()))
                .header(Protocol.H_CONTENT_TYPE, request.contentType());

        applyHeaders(b, request.headers());
        HttpResponse<Void> resp = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        return new AppendResult(resp.statusCode(), next);
    }

    @Override
    public HeadResult head(URI streamUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(streamUrl)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        String contentType = first(resp, Protocol.H_CONTENT_TYPE).orElse(null);
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        Long ttl = parseLongSafe(first(resp, Protocol.H_STREAM_TTL).orElse(null));
        Instant expiresAt = parseInstantSafe(first(resp, Protocol.H_STREAM_EXPIRES_AT).orElse(null));
        return new HeadResult(resp.statusCode(), contentType, next, ttl, expiresAt);
    }

    @Override
    public void delete(URI streamUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(streamUrl)
                .DELETE()
                .build();

        http.send(req, HttpResponse.BodyHandlers.discarding());
    }

    @Override
    public ReadResult readCatchUp(ReadRequest request) throws Exception {
        Offset offset = request.offset() == null ? Offset.beginning() : request.offset();
        URI url = Urls.withQuery(request.streamUrl(), Map.of(Protocol.Q_OFFSET, offset.value()));
        HttpRequest.Builder b = HttpRequest.newBuilder(url).GET();
        if (request.ifNoneMatch() != null) {
            b.header(Protocol.H_IF_NONE_MATCH, request.ifNoneMatch());
        }

        HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        String ct = first(resp, Protocol.H_CONTENT_TYPE).orElse(null);
        boolean upToDate = Protocol.BOOL_TRUE.equalsIgnoreCase(first(resp, Protocol.H_STREAM_UP_TO_DATE).orElse("false"));
        String etag = first(resp, Protocol.H_ETAG).orElse(null);
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

    private static HttpRequest.BodyPublisher bodyPublisher(InputStream in) throws Exception {
        if (in == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        byte[] bytes = readAllBytes(in);
        return HttpRequest.BodyPublishers.ofByteArray(bytes);
    }

    private static void applyHeaders(HttpRequest.Builder b, Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) b.header(e.getKey(), e.getValue());
        }
    }

    private static Optional<String> first(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name);
    }

    private static Offset headerOffset(HttpResponse<?> resp, String name) {
        String v = resp.headers().firstValue(name).orElse(null);
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
        return out.toByteArray();
    }
}