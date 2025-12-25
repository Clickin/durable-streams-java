package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

public final class LongPollLoop {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);
    private final HttpClient http;
    private final LiveLongPollRequest request;

    public LongPollLoop(HttpClient http, LiveLongPollRequest request) {
        this.http = http;
        this.request = request;
    }

    public Flow.Publisher<StreamEvent> publisher() {
        SubmissionPublisher<StreamEvent> pub = new SubmissionPublisher<>();
        Thread t = new Thread(() -> run(pub), "durable-streams-longpoll");
        t.setDaemon(true);
        t.start();
        return pub;
    }

    private void run(SubmissionPublisher<StreamEvent> pub) {
        Offset cur = request.offset();
        String cursor = request.cursor();
        Duration timeout = request.timeout() == null ? DEFAULT_TIMEOUT : request.timeout();

        try {
            while (!pub.isClosed()) {
                Map<String, String> q = new LinkedHashMap<>();
                q.put(Protocol.Q_LIVE, Protocol.LIVE_LONG_POLL);
                q.put(Protocol.Q_OFFSET, cur.value());
                if (cursor != null) q.put(Protocol.Q_CURSOR, cursor);

                URI url = Urls.withQuery(request.streamUrl(), q);
                HttpRequest req = HttpRequest.newBuilder(url)
                        .timeout(timeout.plusSeconds(5))
                        .GET()
                        .build();

                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int status = resp.statusCode();

                if (status == 204) {
                    Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
                    if (next != null) cur = next;
                    cursor = first(resp, Protocol.H_STREAM_CURSOR).orElse(cursor);
                    pub.submit(new StreamEvent.UpToDate(cur));
                    continue;
                }

                if (status == 304) {
                    cursor = first(resp, Protocol.H_STREAM_CURSOR).orElse(cursor);
                    continue;
                }

                if (status != 200) {
                    pub.closeExceptionally(new IllegalStateException("long-poll status=" + status));
                    return;
                }

                Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
                String ct = first(resp, Protocol.H_CONTENT_TYPE).orElse("application/octet-stream");
                byte[] body = resp.body();
                if (body != null && body.length > 0) {
                    pub.submit(new StreamEvent.Data(java.nio.ByteBuffer.wrap(body), ct));
                }
                if (next != null) cur = next;

                boolean upToDate = Protocol.BOOL_TRUE.equalsIgnoreCase(first(resp, Protocol.H_STREAM_UP_TO_DATE).orElse("false"));
                if (upToDate) pub.submit(new StreamEvent.UpToDate(cur));
                cursor = first(resp, Protocol.H_STREAM_CURSOR).orElse(cursor);
            }
        } catch (Exception e) {
            pub.closeExceptionally(e);
        }
    }

    private static Optional<String> first(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name);
    }

    private static Offset headerOffset(HttpResponse<?> resp, String name) {
        String v = resp.headers().firstValue(name).orElse(null);
        return v == null ? null : new Offset(v);
    }
}