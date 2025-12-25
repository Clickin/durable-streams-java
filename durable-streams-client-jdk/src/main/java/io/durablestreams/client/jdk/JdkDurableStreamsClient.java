package io.durablestreams.client.jdk;

import io.durablestreams.core.ControlJson;
import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.SseParser;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * JDK {@link HttpClient}-based implementation.
 */
public final class JdkDurableStreamsClient implements DurableStreamsClient {

    private final HttpClient http;

    public JdkDurableStreamsClient(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    public static JdkDurableStreamsClient defaultClient() {
        return new JdkDurableStreamsClient(HttpClient.newHttpClient());
    }

    @Override
    public CreateResult create(URI streamUrl, String contentType, Map<String, String> headers, InputStream initialBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(streamUrl)
                .PUT(bodyPublisher(initialBody))
                .header(Protocol.H_CONTENT_TYPE, contentType);

        applyHeaders(b, headers);
        HttpResponse<Void> resp = http.send(b.build(), HttpResponse.BodyHandlers.discarding());

        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        return new CreateResult(resp.statusCode(), next);
    }

    @Override
    public AppendResult append(URI streamUrl, String contentType, Map<String, String> headers, InputStream body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(streamUrl)
                .POST(bodyPublisher(body))
                .header(Protocol.H_CONTENT_TYPE, contentType);

        applyHeaders(b, headers);
        HttpResponse<Void> resp = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        return new AppendResult(resp.statusCode(), next);
    }

    @Override
    public ReadResult read(URI streamUrl, Offset offset) throws Exception {
        URI url = Urls.withQuery(streamUrl, Map.of(Protocol.Q_OFFSET, offset.value()));
        HttpRequest req = HttpRequest.newBuilder(url)
                .GET()
                .build();

        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
        String ct = first(resp, Protocol.H_CONTENT_TYPE).orElse(null);
        boolean upToDate = Protocol.BOOL_TRUE.equalsIgnoreCase(first(resp, Protocol.H_STREAM_UP_TO_DATE).orElse("false"));
        String etag = first(resp, Protocol.H_ETAG).orElse(null);
        return new ReadResult(resp.statusCode(), resp.body(), ct, next, upToDate, etag);
    }

    @Override
    public Flow.Publisher<StreamEvent> liveLongPoll(URI streamUrl, Offset offset, String cursor, Duration timeout) {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(offset, "offset");
        Duration to = timeout == null ? Duration.ofSeconds(25) : timeout;

        SubmissionPublisher<StreamEvent> pub = new SubmissionPublisher<>();

        Thread t = new Thread(() -> {
            Offset cur = offset;
            String c = cursor;

            try {
                while (!pub.isClosed()) {
                    Map<String, String> q = new LinkedHashMap<>();
                    q.put(Protocol.Q_LIVE, Protocol.LIVE_LONG_POLL);
                    q.put(Protocol.Q_OFFSET, cur.value());
                    if (c != null) q.put(Protocol.Q_CURSOR, c);

                    URI url = Urls.withQuery(streamUrl, q);

                    HttpRequest req = HttpRequest.newBuilder(url)
                            .timeout(to.plusSeconds(5))
                            .GET()
                            .build();

                    HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());

                    if (resp.statusCode() == 204) {
                        Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
                        pub.submit(new StreamEvent.UpToDate(next));
                        cur = next;
                        c = first(resp, Protocol.H_STREAM_CURSOR).orElse(c);
                        continue;
                    }

                    if (resp.statusCode() != 200) {
                        pub.closeExceptionally(new IllegalStateException("long-poll status=" + resp.statusCode()));
                        return;
                    }

                    Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
                    String ct = first(resp, Protocol.H_CONTENT_TYPE).orElse("application/octet-stream");
                    byte[] body = resp.body();
                    if (body != null && body.length > 0) {
                        pub.submit(new StreamEvent.Data(java.nio.ByteBuffer.wrap(body), ct));
                    }
                    cur = next;

                    boolean upToDate = Protocol.BOOL_TRUE.equalsIgnoreCase(first(resp, Protocol.H_STREAM_UP_TO_DATE).orElse("false"));
                    if (upToDate) pub.submit(new StreamEvent.UpToDate(next));

                    c = first(resp, Protocol.H_STREAM_CURSOR).orElse(c);
                }
            } catch (Exception e) {
                pub.closeExceptionally(e);
            }
        }, "durable-streams-longpoll");
        t.setDaemon(true);
        t.start();

        return pub;
    }

    @Override
    public Flow.Publisher<StreamEvent> liveSse(URI streamUrl, Offset offset) {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(offset, "offset");

        SubmissionPublisher<StreamEvent> pub = new SubmissionPublisher<>();

        Thread t = new Thread(() -> {
            try {
                URI url = Urls.withQuery(streamUrl, Map.of(
                        Protocol.Q_LIVE, Protocol.LIVE_SSE,
                        Protocol.Q_OFFSET, offset.value()
                ));

                HttpRequest req = HttpRequest.newBuilder(url)
                        .header(Protocol.H_ACCEPT, Protocol.CT_EVENT_STREAM)
                        .GET()
                        .build();

                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    pub.closeExceptionally(new IllegalStateException("sse status=" + resp.statusCode()));
                    return;
                }

                try (SseParser parser = new SseParser(resp.body())) {
                    SseParser.Event ev;
                    while ((ev = parser.next()) != null) {
                        String type = ev.eventType();
                        String data = ev.data();

                        if ("data".equals(type)) {
                            pub.submit(new StreamEvent.Data(java.nio.ByteBuffer.wrap(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "text/plain"));
                        } else if ("control".equals(type)) {
                            ControlJson.Control c = ControlJson.parse(data);
                            pub.submit(new StreamEvent.Control(new Offset(c.streamNextOffset()), Optional.ofNullable(c.streamCursor())));
                        } else {
                            // ignore unknown types
                        }
                    }
                }

                pub.close();
            } catch (Exception e) {
                pub.closeExceptionally(e);
            }
        }, "durable-streams-sse");
        t.setDaemon(true);
        t.start();

        return pub;
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

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
        return out.toByteArray();
    }
}
