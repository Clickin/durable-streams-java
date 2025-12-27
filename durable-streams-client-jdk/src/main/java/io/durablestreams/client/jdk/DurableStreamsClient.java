package io.durablestreams.client.jdk;

import io.durablestreams.core.StreamEvent;
import io.durablestreams.http.spi.HttpClientAdapter;

import java.net.URI;
import java.util.concurrent.Flow;

public interface DurableStreamsClient {
    CreateResult create(CreateRequest request) throws Exception;
    AppendResult append(AppendRequest request) throws Exception;
    HeadResult head(URI streamUrl) throws Exception;
    void delete(URI streamUrl) throws Exception;
    ReadResult readCatchUp(ReadRequest request) throws Exception;
    Flow.Publisher<StreamEvent> subscribeLongPoll(LiveLongPollRequest request);
    Flow.Publisher<StreamEvent> subscribeSse(LiveSseRequest request);

    /**
     * Creates a new client with the given HTTP client adapter.
     * Use this to provide a custom HTTP client implementation (e.g., OkHttp, Apache HttpClient).
     *
     * @param httpClientAdapter the HTTP client adapter to use
     * @return a new DurableStreamsClient instance
     */
    static DurableStreamsClient create(HttpClientAdapter httpClientAdapter) {
        return new JdkDurableStreamsClient(httpClientAdapter);
    }
}