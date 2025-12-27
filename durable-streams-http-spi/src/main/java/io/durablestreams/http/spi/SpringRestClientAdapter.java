package io.durablestreams.http.spi;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link HttpClientAdapter} implementation using Spring's {@link RestClient}.
 *
 * <p>Requires Spring Framework 6.1+ / Spring Boot 3.2+.
 * Compatible with Spring 7 / Spring Boot 4.
 *
 * <p>The RestClient can be configured with different underlying HTTP clients:
 * <ul>
 *   <li>JDK HttpClient (default)</li>
 *   <li>Apache HttpClient via {@code HttpComponentsClientHttpRequestFactory}</li>
 *   <li>Jetty via {@code JettyClientHttpRequestFactory}</li>
 *   <li>Reactor Netty via {@code ReactorClientHttpRequestFactory}</li>
 * </ul>
 */
public final class SpringRestClientAdapter implements HttpClientAdapter {

    private final RestClient restClient;

    public SpringRestClientAdapter(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    public static SpringRestClientAdapter create() {
        return new SpringRestClientAdapter(RestClient.create());
    }

    public static SpringRestClientAdapter create(RestClient restClient) {
        return new SpringRestClientAdapter(restClient);
    }

    public static SpringRestClientAdapter create(ClientHttpRequestFactory requestFactory) {
        return new SpringRestClientAdapter(RestClient.builder().requestFactory(requestFactory).build());
    }

    @Override
    public HttpClientResponse send(HttpClientRequest request) throws HttpClientException {
        try {
            ResponseEntity<byte[]> response = executeRequest(request);
            return new ByteArrayResponse(response);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) throw new HttpTimeoutException(e);
            throw new HttpClientException(e);
        } catch (Exception e) {
            throw new HttpClientException(e);
        }
    }

    @Override
    public HttpClientResponse sendStreaming(HttpClientRequest request) throws HttpClientException {
        try {
            ResponseEntity<byte[]> response = executeRequest(request);
            return new StreamingResponse(response);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) throw new HttpTimeoutException(e);
            throw new HttpClientException(e);
        } catch (Exception e) {
            throw new HttpClientException(e);
        }
    }

    private ResponseEntity<byte[]> executeRequest(HttpClientRequest request) {
        RestClient.RequestBodySpec spec = restClient
                .method(HttpMethod.valueOf(request.method()))
                .uri(request.uri());

        request.headers().forEach(spec::header);
        if (request.body() != null) spec.body(request.body());

        return spec.retrieve().toEntity(byte[].class);
    }

    private static boolean isTimeout(ResourceAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) return true;
            if (cause.getClass().getSimpleName().contains("Timeout")) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private static final class ByteArrayResponse implements HttpClientResponse {
        private final ResponseEntity<byte[]> response;

        ByteArrayResponse(ResponseEntity<byte[]> response) { this.response = response; }

        @Override public int statusCode() { return response.getStatusCode().value(); }
        @Override public Optional<String> header(String name) {
            return Optional.ofNullable(response.getHeaders().getFirst(name));
        }
        @Override public byte[] body() { return response.getBody(); }
        @Override public InputStream bodyAsStream() { return null; }
    }

    private static final class StreamingResponse implements HttpClientResponse {
        private final ResponseEntity<byte[]> response;

        StreamingResponse(ResponseEntity<byte[]> response) { this.response = response; }

        @Override public int statusCode() { return response.getStatusCode().value(); }
        @Override public Optional<String> header(String name) {
            return Optional.ofNullable(response.getHeaders().getFirst(name));
        }
        @Override public byte[] body() { return null; }
        @Override public InputStream bodyAsStream() {
            byte[] body = response.getBody();
            return body != null ? new ByteArrayInputStream(body) : null;
        }
    }
}
