package io.durablestreams.http.apache5;

import io.durablestreams.http.spi.HttpClientAdapter;
import io.durablestreams.http.spi.HttpClientException;
import io.durablestreams.http.spi.HttpClientRequest;
import io.durablestreams.http.spi.HttpClientResponse;
import io.durablestreams.http.spi.HttpTimeoutException;

import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link HttpClientAdapter} implementation using Apache HttpClient 5.
 */
public final class ApacheHttpClientAdapter implements HttpClientAdapter {

    private final CloseableHttpClient httpClient;

    public ApacheHttpClientAdapter(CloseableHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /**
     * Creates a new adapter with a default HttpClient.
     * @return a new ApacheHttpClientAdapter
     */
    public static ApacheHttpClientAdapter create() {
        return new ApacheHttpClientAdapter(HttpClients.createDefault());
    }

    /**
     * Creates a new adapter with the specified HttpClient.
     * @param httpClient the Apache HttpClient to use
     * @return a new ApacheHttpClientAdapter
     */
    public static ApacheHttpClientAdapter create(CloseableHttpClient httpClient) {
        return new ApacheHttpClientAdapter(httpClient);
    }

    @Override
    public HttpClientResponse send(HttpClientRequest request) throws HttpClientException {
        try {
            HttpUriRequestBase apacheRequest = toApacheRequest(request);
            return httpClient.execute(apacheRequest, response -> {
                byte[] body = response.getEntity() != null
                        ? EntityUtils.toByteArray(response.getEntity())
                        : null;
                return new ByteArrayResponse(response.getCode(), response.getHeaders(), body);
            });
        } catch (SocketTimeoutException e) {
            throw new HttpTimeoutException(e);
        } catch (Exception e) {
            throw new HttpClientException(e);
        }
    }

    @Override
    public HttpClientResponse sendStreaming(HttpClientRequest request) throws HttpClientException {
        try {
            HttpUriRequestBase apacheRequest = toApacheRequest(request);
            return httpClient.execute(apacheRequest, response -> {
                byte[] body = response.getEntity() != null
                        ? EntityUtils.toByteArray(response.getEntity())
                        : null;
                return new StreamingResponse(response.getCode(), response.getHeaders(), body);
            });
        } catch (SocketTimeoutException e) {
            throw new HttpTimeoutException(e);
        } catch (Exception e) {
            throw new HttpClientException(e);
        }
    }

    private static HttpUriRequestBase toApacheRequest(HttpClientRequest request) {
        URI uri = request.uri();
        String method = request.method();

        HttpUriRequestBase apacheRequest = new HttpUriRequestBase(method, uri);

        // Set body
        if (request.body() != null) {
            apacheRequest.setEntity(new ByteArrayEntity(request.body(), ContentType.APPLICATION_OCTET_STREAM));
        }

        // Set headers
        request.headers().forEach(apacheRequest::setHeader);

        // Set timeout
        if (request.timeout() != null) {
            long millis = request.timeout().toMillis();
            RequestConfig config = RequestConfig.custom()
                    .setResponseTimeout(Timeout.of(millis, TimeUnit.MILLISECONDS))
                    .setConnectionRequestTimeout(Timeout.of(millis, TimeUnit.MILLISECONDS))
                    .build();
            apacheRequest.setConfig(config);
        }

        return apacheRequest;
    }

    private static final class ByteArrayResponse implements HttpClientResponse {
        private final int statusCode;
        private final Header[] headers;
        private final byte[] body;

        ByteArrayResponse(int statusCode, Header[] headers, byte[] body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public Optional<String> header(String name) {
            for (Header h : headers) {
                if (h.getName().equalsIgnoreCase(name)) {
                    return Optional.ofNullable(h.getValue());
                }
            }
            return Optional.empty();
        }

        @Override
        public byte[] body() {
            return body;
        }

        @Override
        public InputStream bodyAsStream() {
            return null;
        }
    }

    private static final class StreamingResponse implements HttpClientResponse {
        private final int statusCode;
        private final Header[] headers;
        private final byte[] body;

        StreamingResponse(int statusCode, Header[] headers, byte[] body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public Optional<String> header(String name) {
            for (Header h : headers) {
                if (h.getName().equalsIgnoreCase(name)) {
                    return Optional.ofNullable(h.getValue());
                }
            }
            return Optional.empty();
        }

        @Override
        public byte[] body() {
            return null;
        }

        @Override
        public InputStream bodyAsStream() {
            return body != null ? new ByteArrayInputStream(body) : null;
        }
    }
}
