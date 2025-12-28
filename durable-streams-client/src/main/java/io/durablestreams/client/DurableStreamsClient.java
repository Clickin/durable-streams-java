package io.durablestreams.client;

import io.durablestreams.core.StreamEvent;

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

    static DurableStreamsClient create() {
        return builder().build();
    }

    static DurableStreamsClient create(java.net.http.HttpClient httpClient) {
        return builder().jdkHttpClient(httpClient).build();
    }

    static DurableStreamsClientBuilder builder() {
        return new DurableStreamsClientBuilder();
    }
}
