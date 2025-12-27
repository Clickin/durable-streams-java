package io.durablestreams.http.spi;

/**
 * Abstraction for HTTP client implementations.
 *
 * <p>This interface allows the Durable Streams client to work with different
 * HTTP client libraries (JDK HttpClient, Apache HttpClient, OkHttp, etc.)
 * without direct dependency on any specific implementation.
 *
 * <p>Implementations should be thread-safe and reusable.
 *
 * <p>Example usage:
 * <pre>{@code
 * HttpClientAdapter adapter = JdkHttpClientAdapter.create();
 * HttpClientRequest request = HttpClientRequest.get(URI.create("http://example.com")).build();
 * HttpClientResponse response = adapter.send(request);
 * }</pre>
 */
public interface HttpClientAdapter {

    /**
     * Sends an HTTP request and returns the response with body as byte array.
     *
     * <p>Use this method for requests where the entire response body should
     * be read into memory (e.g., JSON responses, small payloads).
     *
     * @param request the HTTP request to send
     * @return the HTTP response with body as bytes
     * @throws HttpClientException if the request fails
     * @throws HttpTimeoutException if the request times out
     */
    HttpClientResponse send(HttpClientRequest request) throws HttpClientException;

    /**
     * Sends an HTTP request and returns the response with body as a stream.
     *
     * <p>Use this method for streaming responses (e.g., Server-Sent Events)
     * where the response should be read incrementally.
     *
     * <p>The caller is responsible for closing the returned stream.
     *
     * @param request the HTTP request to send
     * @return the HTTP response with body as InputStream
     * @throws HttpClientException if the request fails
     * @throws HttpTimeoutException if the request times out
     */
    HttpClientResponse sendStreaming(HttpClientRequest request) throws HttpClientException;
}
