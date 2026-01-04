package io.durablestreams.client;

import io.durablestreams.core.Headers;
import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.core.Urls;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Internal implementation of the Long-Poll loop.
 *
 * <p>This class is not intended to be used directly by clients.
 */
public final class LongPollLoop {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);
    private final DurableStreamsTransport transport;
    private final LiveLongPollRequest request;

    public LongPollLoop(DurableStreamsTransport transport, LiveLongPollRequest request) {
        this.transport = transport;
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
                TransportRequest req = new TransportRequest("GET", url, Map.of(), null, timeout.plusSeconds(5));
                TransportResponse<byte[]> resp = transport.sendBytes(req);
                int status = resp.status();

                if (status == 204) {
                    Offset next = headerOffset(resp.headers(), Protocol.H_STREAM_NEXT_OFFSET);
                    if (next != null) cur = next;
                    cursor = first(resp.headers(), Protocol.H_STREAM_CURSOR).orElse(cursor);
                    pub.submit(new StreamEvent.UpToDate(cur));
                    continue;
                }

                if (status == 304) {
                    cursor = first(resp.headers(), Protocol.H_STREAM_CURSOR).orElse(cursor);
                    continue;
                }

                if (status != 200) {
                    pub.closeExceptionally(new IllegalStateException("long-poll status=" + status));
                    return;
                }

                Offset next = headerOffset(resp.headers(), Protocol.H_STREAM_NEXT_OFFSET);
                String ct = first(resp.headers(), Protocol.H_CONTENT_TYPE).orElse("application/octet-stream");
                byte[] body = resp.body();
                if (body != null && body.length > 0) {
                    pub.submit(new StreamEvent.Data(java.nio.ByteBuffer.wrap(body), ct));
                }
                if (next != null) cur = next;

                boolean upToDate = Protocol.BOOL_TRUE.equalsIgnoreCase(first(resp.headers(), Protocol.H_STREAM_UP_TO_DATE).orElse("false"));
                if (upToDate) pub.submit(new StreamEvent.UpToDate(cur));
                cursor = first(resp.headers(), Protocol.H_STREAM_CURSOR).orElse(cursor);
            }
        } catch (Exception e) {
            pub.closeExceptionally(e);
        }
    }

    private static Optional<String> first(Map<String, ? extends Iterable<String>> headers, String name) {
        return Headers.firstValue(headers, name);
    }

    private static Offset headerOffset(Map<String, ? extends Iterable<String>> headers, String name) {
        String v = first(headers, name).orElse(null);
        return v == null ? null : new Offset(v);
    }
}
