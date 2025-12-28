package io.durablestreams.client;

import java.net.http.HttpClient;
import java.util.Objects;

public final class DurableStreamsClientBuilder {
    private DurableStreamsTransport transport;

    public DurableStreamsClientBuilder transport(DurableStreamsTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        return this;
    }

    public DurableStreamsClientBuilder jdkHttpClient(HttpClient httpClient) {
        this.transport = new JdkHttpTransport(Objects.requireNonNull(httpClient, "httpClient"));
        return this;
    }

    public DurableStreamsClient build() {
        DurableStreamsTransport resolved = transport;
        if (resolved == null) {
            resolved = new JdkHttpTransport(HttpClient.newHttpClient());
        }
        return new JdkDurableStreamsClient(resolved);
    }
}
