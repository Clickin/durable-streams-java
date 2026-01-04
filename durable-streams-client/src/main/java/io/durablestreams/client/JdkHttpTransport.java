package io.durablestreams.client;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Objects;

/**
 * Transport implementation using {@link java.net.http.HttpClient}.
 */
public final class JdkHttpTransport implements DurableStreamsTransport {
    private final HttpClient http;

    /**
     * Creates a new transport.
     *
     * @param http the JDK HttpClient to use
     */
    public JdkHttpTransport(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public TransportResponse<Void> sendDiscarding(TransportRequest request) throws Exception {
        HttpResponse<Void> resp = http.send(buildRequest(request), HttpResponse.BodyHandlers.discarding());
        return new TransportResponse<>(resp.statusCode(), resp.headers().map(), null);
    }

    @Override
    public TransportResponse<byte[]> sendBytes(TransportRequest request) throws Exception {
        HttpResponse<byte[]> resp = http.send(buildRequest(request), HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        return new TransportResponse<>(resp.statusCode(), resp.headers().map(), body);
    }

    @Override
    public TransportResponse<InputStream> sendStream(TransportRequest request) throws Exception {
        HttpResponse<InputStream> resp = http.send(buildRequest(request), HttpResponse.BodyHandlers.ofInputStream());
        return new TransportResponse<>(resp.statusCode(), resp.headers().map(), resp.body());
    }

    private static HttpRequest buildRequest(TransportRequest request) {
        HttpRequest.BodyPublisher body = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());

        HttpRequest.Builder builder = HttpRequest.newBuilder(request.url())
                .method(request.method(), body);

        if (request.timeout() != null) {
            builder.timeout(request.timeout());
        }

        for (Map.Entry<String, ? extends Iterable<String>> entry : request.headers().entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            Iterable<String> values = entry.getValue();
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (value != null) {
                    builder.header(name, value);
                }
            }
        }

        return builder.build();
    }
}
