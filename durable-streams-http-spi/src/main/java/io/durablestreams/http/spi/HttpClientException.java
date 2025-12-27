package io.durablestreams.http.spi;

/**
 * Exception thrown when an HTTP operation fails.
 * Wraps underlying implementation-specific exceptions.
 */
public class HttpClientException extends Exception {

    public HttpClientException(String message) {
        super(message);
    }

    public HttpClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public HttpClientException(Throwable cause) {
        super(cause);
    }
}
