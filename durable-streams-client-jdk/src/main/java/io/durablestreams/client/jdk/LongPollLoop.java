package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;
import io.durablestreams.http.spi.HttpClientAdapter;
import io.durablestreams.http.spi.HttpClientRequest;
import io.durablestreams.http.spi.HttpClientResponse;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

public final class LongPollLoop {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);
    private final HttpClientAdapter http;
    private final LiveLongPollRequest request;

    public LongPollLoop(HttpClientAdapter http, LiveLongPollRequest request) {
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
                HttpClientRequest req = HttpClientRequest.get(url)
                        .timeout(timeout.plusSeconds(5))
                        .build();

                HttpClientResponse resp = http.send(req);
                int status = resp.statusCode();

                if (status == 204) {
                    Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
                    if (next != null) cur = next;
                    cursor = resp.header(Protocol.H_STREAM_CURSOR).orElse(cursor);
                    pub.submit(new StreamEvent.UpToDate(cur));
                    continue;
                }

                if (status == 304) {
                    cursor = resp.header(Protocol.H_STREAM_CURSOR).orElse(cursor);
                    continue;
                }

                if (status != 200) {
                    pub.closeExceptionally(new IllegalStateException("long-poll status=" + status));
                    return;
                }

                Offset next = headerOffset(resp, Protocol.H_STREAM_NEXT_OFFSET);
                String ct = resp.header(Protocol.H_CONTENT_TYPE).orElse("application/octet-stream");
                byte[] body = resp.body();
                if (body != null && body.length > 0) {
                    pub.submit(new StreamEvent.Data(java.nio.ByteBuffer.wrap(body), ct));
                }
                if (next != null) cur = next;

                boolean upToDate = Protocol.BOOL_TRUE.equalsIgnoreCase(resp.header(Protocol.H_STREAM_UP_TO_DATE).orElse("false"));
                if (upToDate) pub.submit(new StreamEvent.UpToDate(cur));
                cursor = resp.header(Protocol.H_STREAM_CURSOR).orElse(cursor);
            }
        } catch (Exception e) {
            pub.closeExceptionally(e);
        }
    }

    private static Offset headerOffset(HttpClientResponse resp, String name) {
        String v = resp.header(name).orElse(null);
        return v == null ? null : new Offset(v);
    }
}