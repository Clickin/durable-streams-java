package io.github.clickin.server.spi;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Rate limiting SPI for Durable Streams protocol.
 *
 * <p>Implementations control request rate limiting. When a request is rejected,
 * the server returns 429 Too Many Requests with an optional Retry-After header.
 *
 * <p>The protocol specification requires servers to implement rate limiting as a
 * security measure, but leaves the algorithm implementation-specific.
 */
public interface RateLimiter {

    /**
     * Check if a request should be allowed.
     *
     * @param streamUrl the stream URL being accessed
     * @param clientId optional client identifier (e.g., IP address, API key)
     * @return result indicating whether the request is allowed
     */
    Result tryAcquire(URI streamUrl, String clientId);

    /**
     * Result of a rate limit check.
     */
    sealed interface Result permits Result.Allowed, Result.Rejected {

        /**
         * Request is allowed to proceed.
         */
        record Allowed() implements Result {}

        /**
         * Request is rejected due to rate limiting.
         *
         * @param retryAfter optional duration after which the client may retry
         */
        record Rejected(Optional<Duration> retryAfter) implements Result {
            public Rejected() {
                this(Optional.empty());
            }

            public Rejected(Duration retryAfter) {
                this(Optional.ofNullable(retryAfter));
            }
        }
    }

    /**
     * No-op rate limiter that allows all requests.
     */
    static RateLimiter permitAll() {
        return (url, clientId) -> new Result.Allowed();
    }
}
