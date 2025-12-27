package io.durablestreams.http.spi;

/**
 * Exception thrown when an HTTP request times out.
 * Allows callers to distinguish timeout errors from other failures.
 */
public class HttpTimeoutException extends HttpClientException {

    public HttpTimeoutException(String message) {
        super(message);
    }

    public HttpTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    public HttpTimeoutException(Throwable cause) {
        super(cause);
    }
}
