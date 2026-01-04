package io.github.clickin.client;

import java.net.http.HttpClient;
import java.util.Objects;

/**
 * Builder for {@link DurableStreamsClient}.
 *
 * <p>Allows configuring the transport layer (e.g. JDK HttpClient or custom).
 */
public final class DurableStreamsClientBuilder {
    private DurableStreamsTransport transport;

    /**
     * Sets a custom transport implementation.
     *
     * @param transport the transport to use
     * @return this builder
     */
    public DurableStreamsClientBuilder transport(DurableStreamsTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        return this;
    }

    /**
     * Uses the default JDK HttpClient transport with a provided HttpClient instance.
     *
     * @param httpClient the JDK HttpClient to use
     * @return this builder
     */
    public DurableStreamsClientBuilder jdkHttpClient(HttpClient httpClient) {
        this.transport = new JdkHttpTransport(Objects.requireNonNull(httpClient, "httpClient"));
        return this;
    }

    /**
     * Builds the client.
     *
     * <p>If no transport is configured, a default JDK HttpClient-based transport is created.
     *
     * @return the new client instance
     */
    public DurableStreamsClient build() {
        DurableStreamsTransport resolved = transport;
        if (resolved == null) {
            resolved = new JdkHttpTransport(HttpClient.newHttpClient());
        }
        return new JdkDurableStreamsClient(resolved);
    }
}
