package io.durablestreams.http.spi;

import java.io.InputStream;
import java.util.Optional;

/**
 * Represents an HTTP response from an {@link HttpClientAdapter}.
 *
 * <p>Implementations must ensure that either {@link #body()} or {@link #bodyAsStream()}
 * returns the response content, depending on how the request was made.
 */
public interface HttpClientResponse {

    /**
     * Returns the HTTP status code.
     * @return the status code (e.g., 200, 404, 500)
     */
    int statusCode();

    /**
     * Returns the first value for the specified header name.
     * @param name the header name (case-insensitive)
     * @return the header value, or empty if not present
     */
    Optional<String> header(String name);

    /**
     * Returns the response body as a byte array.
     * Use this for non-streaming responses.
     * @return the body bytes, or null if not available
     */
    byte[] body();

    /**
     * Returns the response body as an input stream.
     * Use this for streaming responses (e.g., SSE).
     * @return the body stream, or null if not available
     */
    InputStream bodyAsStream();
}
