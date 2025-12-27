package io.durablestreams.http.spring;

import io.durablestreams.http.spi.HttpClientAdapter;
import io.durablestreams.http.spi.HttpClientException;
import io.durablestreams.http.spi.HttpClientRequest;
import io.durablestreams.http.spi.HttpClientResponse;
import io.durablestreams.http.spi.HttpTimeoutException;

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
 * <p>This adapter integrates with Spring 6.1+ / Spring Boot 3.2+ RestClient,
 * which is the modern synchronous HTTP client in Spring Framework.
 * It is fully compatible with Spring 7 / Spring Boot 4.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Using default RestClient
 * HttpClientAdapter adapter = SpringRestClientAdapter.create();
 *
 * // Using custom RestClient (e.g., with custom timeouts, interceptors)
 * RestClient customClient = RestClient.builder()
 *     .requestFactory(new HttpComponentsClientHttpRequestFactory())
 *     .defaultHeader("User-Agent", "MyApp/1.0")
 *     .build();
 * HttpClientAdapter adapter = SpringRestClientAdapter.create(customClient);
 *
 * // Use with DurableStreamsClient
 * DurableStreamsClient client = DurableStreamsClient.create(adapter);
 * }</pre>
 *
 * <p>The RestClient can be configured to use different underlying HTTP clients:
 * <ul>
 *   <li>JDK HttpClient (default in Spring 6.1+)</li>
 *   <li>Apache HttpClient via {@code HttpComponentsClientHttpRequestFactory}</li>
 *   <li>Jetty HttpClient via {@code JettyClientHttpRequestFactory}</li>
 *   <li>Reactor Netty via {@code ReactorClientHttpRequestFactory}</li>
 * </ul>
 */
public final class SpringRestClientAdapter implements HttpClientAdapter {

    private final RestClient restClient;

    public SpringRestClientAdapter(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    /**
     * Creates a new adapter with a default RestClient.
     * @return a new SpringRestClientAdapter
     */
    public static SpringRestClientAdapter create() {
        return new SpringRestClientAdapter(RestClient.create());
    }

    /**
     * Creates a new adapter with the specified RestClient.
     * @param restClient the RestClient to use
     * @return a new SpringRestClientAdapter
     */
    public static SpringRestClientAdapter create(RestClient restClient) {
        return new SpringRestClientAdapter(restClient);
    }

    /**
     * Creates a new adapter with a RestClient built from the specified factory.
     * @param requestFactory the ClientHttpRequestFactory to use
     * @return a new SpringRestClientAdapter
     */
    public static SpringRestClientAdapter create(ClientHttpRequestFactory requestFactory) {
        RestClient client = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        return new SpringRestClientAdapter(client);
    }

    @Override
    public HttpClientResponse send(HttpClientRequest request) throws HttpClientException {
        try {
            ResponseEntity<byte[]> response = executeRequest(request, byte[].class);
            return new ByteArrayResponse(response);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new HttpTimeoutException(e);
            }
            throw new HttpClientException(e);
        } catch (Exception e) {
            throw new HttpClientException(e);
        }
    }

    @Override
    public HttpClientResponse sendStreaming(HttpClientRequest request) throws HttpClientException {
        try {
            ResponseEntity<byte[]> response = executeRequest(request, byte[].class);
            return new StreamingResponse(response);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new HttpTimeoutException(e);
            }
            throw new HttpClientException(e);
        } catch (Exception e) {
            throw new HttpClientException(e);
        }
    }

    private <T> ResponseEntity<T> executeRequest(HttpClientRequest request, Class<T> responseType) {
        RestClient.RequestBodySpec spec = restClient
                .method(HttpMethod.valueOf(request.method()))
                .uri(request.uri());

        // Set headers
        request.headers().forEach(spec::header);

        // Set body if present
        if (request.body() != null) {
            spec.body(request.body());
        }

        return spec.retrieve().toEntity(responseType);
    }

    private static boolean isTimeout(ResourceAccessException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            if (cause.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static final class ByteArrayResponse implements HttpClientResponse {
        private final ResponseEntity<byte[]> response;

        ByteArrayResponse(ResponseEntity<byte[]> response) {
            this.response = response;
        }

        @Override
        public int statusCode() {
            return response.getStatusCode().value();
        }

        @Override
        public Optional<String> header(String name) {
            HttpHeaders headers = response.getHeaders();
            String value = headers.getFirst(name);
            return Optional.ofNullable(value);
        }

        @Override
        public byte[] body() {
            return response.getBody();
        }

        @Override
        public InputStream bodyAsStream() {
            return null;
        }
    }

    private static final class StreamingResponse implements HttpClientResponse {
        private final ResponseEntity<byte[]> response;

        StreamingResponse(ResponseEntity<byte[]> response) {
            this.response = response;
        }

        @Override
        public int statusCode() {
            return response.getStatusCode().value();
        }

        @Override
        public Optional<String> header(String name) {
            HttpHeaders headers = response.getHeaders();
            String value = headers.getFirst(name);
            return Optional.ofNullable(value);
        }

        @Override
        public byte[] body() {
            return null;
        }

        @Override
        public InputStream bodyAsStream() {
            byte[] body = response.getBody();
            return body != null ? new ByteArrayInputStream(body) : null;
        }
    }
}
