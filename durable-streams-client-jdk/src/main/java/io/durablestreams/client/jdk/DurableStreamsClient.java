package io.durablestreams.client.jdk;

import io.durablestreams.core.StreamEvent;
import io.durablestreams.http.spi.HttpClientAdapter;
import io.durablestreams.http.spi.JdkHttpClientAdapter;

import java.net.URI;
import java.util.Objects;
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
     * Creates a new client with default JDK HttpClient.
     * @return a new DurableStreamsClient instance
     */
    static DurableStreamsClient create() {
        return new JdkDurableStreamsClient(JdkHttpClientAdapter.create());
    }

    /**
     * Creates a new builder for configuring the client.
     * @return a new Builder instance
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating a DurableStreamsClient with custom configuration.
     */
    final class Builder {
        private HttpClientAdapter httpClient;

        private Builder() {}

        /**
         * Sets a custom HTTP client adapter.
         * Use this to provide OkHttp, Apache HttpClient, Spring RestClient, etc.
         *
         * @param httpClient the HTTP client adapter to use
         * @return this builder
         */
        public Builder httpClient(HttpClientAdapter httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        /**
         * Builds the DurableStreamsClient.
         * If no HTTP client was set, uses the default JDK HttpClient.
         *
         * @return a new DurableStreamsClient instance
         */
        public DurableStreamsClient build() {
            HttpClientAdapter adapter = httpClient != null ? httpClient : JdkHttpClientAdapter.create();
            return new JdkDurableStreamsClient(adapter);
        }
    }
}