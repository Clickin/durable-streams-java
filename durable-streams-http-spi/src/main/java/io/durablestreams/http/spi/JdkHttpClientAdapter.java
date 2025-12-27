package io.durablestreams.http.spi;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link HttpClientAdapter} implementation using the JDK 11+ HttpClient.
 * This is the default implementation when no other HTTP client library is available.
 */
public final class JdkHttpClientAdapter implements HttpClientAdapter {

    private final HttpClient httpClient;

    public JdkHttpClientAdapter(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /**
     * Creates a new adapter with a default HttpClient.
     * @return a new JdkHttpClientAdapter
     */
    public static JdkHttpClientAdapter create() {
        return new JdkHttpClientAdapter(HttpClient.newHttpClient());
    }

    /**
     * Creates a new adapter with the specified HttpClient.
     * @param httpClient the HttpClient to use
     * @return a new JdkHttpClientAdapter
     */
    public static JdkHttpClientAdapter create(HttpClient httpClient) {
        return new JdkHttpClientAdapter(httpClient);
    }

    @Override
    public HttpClientResponse send(HttpClientRequest request) throws HttpClientException {
        try {
            HttpRequest jdkRequest = toJdkRequest(request);
            HttpResponse<byte[]> response = httpClient.send(jdkRequest, HttpResponse.BodyHandlers.ofByteArray());
            return new ByteArrayResponse(response);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new HttpTimeoutException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpClientException("Request interrupted", e);
        } catch (Exception e) {
            throw new HttpClientException(e);
        }
    }

    @Override
    public HttpClientResponse sendStreaming(HttpClientRequest request) throws HttpClientException {
        try {
            HttpRequest jdkRequest = toJdkRequest(request);
            HttpResponse<InputStream> response = httpClient.send(jdkRequest, HttpResponse.BodyHandlers.ofInputStream());
            return new StreamingResponse(response);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new HttpTimeoutException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HttpClientException("Request interrupted", e);
        } catch (Exception e) {
            throw new HttpClientException(e);
        }
    }

    private static HttpRequest toJdkRequest(HttpClientRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri());

        HttpRequest.BodyPublisher bodyPublisher = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());

        builder.method(request.method(), bodyPublisher);
        request.headers().forEach(builder::header);

        if (request.timeout() != null) {
            builder.timeout(request.timeout());
        }

        return builder.build();
    }

    private static final class ByteArrayResponse implements HttpClientResponse {
        private final HttpResponse<byte[]> response;

        ByteArrayResponse(HttpResponse<byte[]> response) {
            this.response = response;
        }

        @Override
        public int statusCode() {
            return response.statusCode();
        }

        @Override
        public Optional<String> header(String name) {
            return response.headers().firstValue(name);
        }

        @Override
        public byte[] body() {
            return response.body();
        }

        @Override
        public InputStream bodyAsStream() {
            return null;
        }
    }

    private static final class StreamingResponse implements HttpClientResponse {
        private final HttpResponse<InputStream> response;

        StreamingResponse(HttpResponse<InputStream> response) {
            this.response = response;
        }

        @Override
        public int statusCode() {
            return response.statusCode();
        }

        @Override
        public Optional<String> header(String name) {
            return response.headers().firstValue(name);
        }

        @Override
        public byte[] body() {
            return null;
        }

        @Override
        public InputStream bodyAsStream() {
            return response.body();
        }
    }
}
