package io.durablestreams.client.jdk;

import io.durablestreams.core.ControlJson;
import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.SseParser;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

public final class SseLoop {

    private final HttpClient http;
    private final LiveSseRequest request;

    public SseLoop(HttpClient http, LiveSseRequest request) {
        this.http = http;
        this.request = request;
    }

    public Flow.Publisher<StreamEvent> publisher() {
        SubmissionPublisher<StreamEvent> pub = new SubmissionPublisher<>();
        Thread t = new Thread(() -> run(pub), "durable-streams-sse");
        t.setDaemon(true);
        t.start();
        return pub;
    }

    private void run(SubmissionPublisher<StreamEvent> pub) {
        Offset cur = request.offset();

        try {
            while (!pub.isClosed()) {
                URI url = Urls.withQuery(request.streamUrl(), Map.of(
                        Protocol.Q_LIVE, Protocol.LIVE_SSE,
                        Protocol.Q_OFFSET, cur.value()
                ));

                HttpRequest req = HttpRequest.newBuilder(url)
                        .timeout(Duration.ofSeconds(65))
                        .header(Protocol.H_ACCEPT, Protocol.CT_EVENT_STREAM)
                        .GET()
                        .build();

                try {
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
                                pub.submit(new StreamEvent.Data(java.nio.ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)), "text/plain"));
                            } else if ("control".equals(type)) {
                                ControlJson.Control c = ControlJson.parse(data);
                                cur = new Offset(c.streamNextOffset());
                                pub.submit(new StreamEvent.Control(cur, Optional.ofNullable(c.streamCursor())));
                            }
                        }
                    }
                } catch (java.net.http.HttpTimeoutException ignored) {
                    continue;
                }
            }
        } catch (Exception e) {
            pub.closeExceptionally(e);
        }
    }
}