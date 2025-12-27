package io.durablestreams.client.jdk;

import io.durablestreams.core.ControlJson;
import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.SseParser;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;
import io.durablestreams.http.spi.HttpClientAdapter;
import io.durablestreams.http.spi.HttpClientRequest;
import io.durablestreams.http.spi.HttpClientResponse;
import io.durablestreams.http.spi.HttpTimeoutException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

public final class SseLoop {

    private final HttpClientAdapter http;
    private final LiveSseRequest request;

    public SseLoop(HttpClientAdapter http, LiveSseRequest request) {
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

                HttpClientRequest req = HttpClientRequest.get(url)
                        .timeout(Duration.ofSeconds(65))
                        .header(Protocol.H_ACCEPT, Protocol.CT_EVENT_STREAM)
                        .build();

                try {
                    HttpClientResponse resp = http.sendStreaming(req);
                    if (resp.statusCode() != 200) {
                        pub.closeExceptionally(new IllegalStateException("sse status=" + resp.statusCode()));
                        return;
                    }

                    try (SseParser parser = new SseParser(resp.bodyAsStream())) {
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
                } catch (HttpTimeoutException ignored) {
                    continue;
                }
            }
        } catch (Exception e) {
            pub.closeExceptionally(e);
        }
    }
}