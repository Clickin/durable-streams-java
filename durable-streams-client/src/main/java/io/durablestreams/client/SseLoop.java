package io.durablestreams.client;

import io.durablestreams.core.ControlJson;
import io.durablestreams.core.Headers;
import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.SseParser;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Internal implementation of the SSE loop.
 *
 * <p>This class is not intended to be used directly by clients.
 */
public final class SseLoop {

    private final DurableStreamsTransport transport;
    private final LiveSseRequest request;

    public SseLoop(DurableStreamsTransport transport, LiveSseRequest request) {
        this.transport = transport;
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
        String streamContentType;

        try {
            streamContentType = resolveStreamContentType();
        } catch (Exception e) {
            pub.closeExceptionally(e);
            return;
        }

        try {
            while (!pub.isClosed()) {
                URI url = Urls.withQuery(request.streamUrl(), Map.of(
                        Protocol.Q_LIVE, Protocol.LIVE_SSE,
                        Protocol.Q_OFFSET, cur.value()
                ));

                Map<String, Iterable<String>> headers = Map.of(Protocol.H_ACCEPT, List.of(Protocol.CT_EVENT_STREAM));
                TransportRequest req = new TransportRequest("GET", url, headers, null, Duration.ofSeconds(65));

                try {
                    TransportResponse<InputStream> resp = transport.sendStream(req);
                    if (resp.status() != 200) {
                        pub.closeExceptionally(new IllegalStateException("sse status=" + resp.status()));
                        return;
                    }

                    try (SseParser parser = new SseParser(resp.body())) {
                        SseParser.Event ev;
                        while ((ev = parser.next()) != null) {
                            String type = ev.eventType();
                            String data = ev.data();

                            if ("data".equals(type)) {
                                pub.submit(new StreamEvent.Data(java.nio.ByteBuffer.wrap(data.getBytes(StandardCharsets.UTF_8)), streamContentType));
                            } else if ("control".equals(type)) {
                                ControlJson.Control c = ControlJson.parse(data);
                                cur = new Offset(c.streamNextOffset());
                                pub.submit(new StreamEvent.Control(cur, Optional.ofNullable(c.streamCursor())));
                                if (ControlJson.parseUpToDate(data)) {
                                    pub.submit(new StreamEvent.UpToDate(cur));
                                }
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

    private String resolveStreamContentType() throws Exception {
        TransportRequest req = new TransportRequest("HEAD", request.streamUrl(), Map.of(), null, null);
        TransportResponse<Void> resp = transport.sendDiscarding(req);
        if (resp.status() >= 400) {
            throw new IllegalStateException("head status=" + resp.status());
        }
        return first(resp.headers(), Protocol.H_CONTENT_TYPE).orElse("application/octet-stream");
    }

    private static Optional<String> first(Map<String, ? extends Iterable<String>> headers, String name) {
        return Headers.firstValue(headers, name);
    }
}
