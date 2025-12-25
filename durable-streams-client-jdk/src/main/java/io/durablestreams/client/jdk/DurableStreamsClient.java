package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;
import io.durablestreams.core.StreamEvent;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Client API for Durable Streams (JDK/Flow based).
 */
public interface DurableStreamsClient {

    /**
     * Create stream (PUT).
     *
     * @param streamUrl stream base URL (no query)
     * @param contentType Content-Type
     * @param headers additional protocol headers (Stream-TTL / Stream-Expires-At etc.)
     * @param initialBody optional body (may be null)
     */
    CreateResult create(URI streamUrl, String contentType, Map<String, String> headers, InputStream initialBody) throws Exception;

    /**
     * Append (POST).
     */
    AppendResult append(URI streamUrl, String contentType, Map<String, String> headers, InputStream body) throws Exception;

    /**
     * Catch-up read (GET).
     */
    ReadResult read(URI streamUrl, Offset offset) throws Exception;

    /**
     * Live via long-poll.
     *
     * <p>Publisher emits {@link StreamEvent.Data} chunks and {@link StreamEvent.UpToDate} heartbeats.
     */
    Flow.Publisher<StreamEvent> liveLongPoll(URI streamUrl, Offset offset, String cursor, Duration timeout);

    /**
     * Live via SSE.
     *
     * <p>Publisher emits {@link StreamEvent.Data} and {@link StreamEvent.Control}.
     */
    Flow.Publisher<StreamEvent> liveSse(URI streamUrl, Offset offset);

    record CreateResult(int status, Offset nextOffset) {}
    record AppendResult(int status, Offset nextOffset) {}
    record ReadResult(int status, byte[] body, String contentType, Offset nextOffset, boolean upToDate, String etag) {}
}
