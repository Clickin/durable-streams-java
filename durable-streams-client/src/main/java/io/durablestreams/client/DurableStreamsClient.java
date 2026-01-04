package io.durablestreams.client;

import io.durablestreams.core.StreamEvent;

import java.net.URI;
import java.util.concurrent.Flow;

/**
 * High-level client for the Durable Streams protocol.
 *
 * <p>Supports creation, appending, reading (catch-up), and live subscriptions (Long-Poll or SSE).
 * Use {@link #builder()} or {@link #create()} to obtain an instance.
 */
public interface DurableStreamsClient {
    /**
     * Creates a new stream.
     *
     * @param request the creation request details
     * @return the result of the creation operation
     * @throws Exception if an error occurs during transport or processing
     */
    CreateResult create(CreateRequest request) throws Exception;

    /**
     * Appends data to an existing stream.
     *
     * @param request the append request details
     * @return the result of the append operation
     * @throws Exception if an error occurs during transport or processing
     */
    AppendResult append(AppendRequest request) throws Exception;

    /**
     * Retrieves metadata for a stream (HEAD request).
     *
     * @param streamUrl the absolute URL of the stream
     * @return the metadata result
     * @throws Exception if an error occurs during transport or processing
     */
    HeadResult head(URI streamUrl) throws Exception;

    /**
     * Deletes a stream.
     *
     * @param streamUrl the absolute URL of the stream to delete
     * @throws Exception if an error occurs during transport or processing
     */
    void delete(URI streamUrl) throws Exception;

    /**
     * Reads from a stream in catch-up mode (single fetch).
     *
     * @param request the read request details
     * @return the read result
     * @throws Exception if an error occurs during transport or processing
     */
    ReadResult readCatchUp(ReadRequest request) throws Exception;

    /**
     * Subscribes to a stream using Long-Polling.
     *
     * <p>Returns a publisher of stream events. The implementation handles reconnects and cursor management.
     *
     * @param request the long-poll subscription configuration
     * @return a reactive publisher of {@link StreamEvent}
     */
    Flow.Publisher<StreamEvent> subscribeLongPoll(LiveLongPollRequest request);

    /**
     * Subscribes to a stream using Server-Sent Events (SSE).
     *
     * <p>Returns a publisher of stream events. The implementation handles SSE parsing and control events.
     *
     * @param request the SSE subscription configuration
     * @return a reactive publisher of {@link StreamEvent}
     */
    Flow.Publisher<StreamEvent> subscribeSse(LiveSseRequest request);

    /**
     * Creates a default client using the JDK internal HttpClient.
     *
     * @return a new client instance
     */
    static DurableStreamsClient create() {
        return builder().build();
    }

    /**
     * Creates a client using a specific JDK HttpClient instance.
     *
     * @param httpClient the JDK HttpClient to use
     * @return a new client instance
     */
    static DurableStreamsClient create(java.net.http.HttpClient httpClient) {
        return builder().jdkHttpClient(httpClient).build();
    }

    /**
     * Creates a builder for customizing the client.
     *
     * @return a new {@link DurableStreamsClientBuilder}
     */
    static DurableStreamsClientBuilder builder() {
        return new DurableStreamsClientBuilder();
    }
}
