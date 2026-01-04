package io.github.clickin.server.core;

import io.github.clickin.server.spi.RateLimiter;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket rate limiter implementation.
 *
 * <p>This is a reference implementation suitable for single-instance deployments.
 * For distributed systems, use a Redis-backed or similar implementation.
 *
 * <p>Each client gets a bucket that refills at a configured rate. Requests consume
 * tokens; when the bucket is empty, requests are rejected with 429.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private final int maxTokens;
    private final double refillRatePerSecond;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Creates a rate limiter with default settings (100 requests/minute burst, 10 requests/second sustained).
     */
    public TokenBucketRateLimiter() {
        this(100, 10.0, Clock.systemUTC());
    }

    /**
     * Creates a rate limiter with custom settings.
     *
     * @param maxTokens maximum burst capacity
     * @param refillRatePerSecond tokens added per second
     * @param clock clock for time tracking
     */
    public TokenBucketRateLimiter(int maxTokens, double refillRatePerSecond, Clock clock) {
        if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be positive");
        if (refillRatePerSecond <= 0) throw new IllegalArgumentException("refillRatePerSecond must be positive");
        this.maxTokens = maxTokens;
        this.refillRatePerSecond = refillRatePerSecond;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Result tryAcquire(URI streamUrl, String clientId) {
        String key = clientId != null ? clientId : "anonymous";
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(maxTokens, clock.instant()));
        return bucket.tryConsume(clock.instant(), refillRatePerSecond, maxTokens);
    }

    /**
     * Clears all rate limit state. Useful for testing.
     */
    public void reset() {
        buckets.clear();
    }

    private static final class Bucket {
        private double tokens;
        private Instant lastRefill;

        Bucket(double tokens, Instant now) {
            this.tokens = tokens;
            this.lastRefill = now;
        }

        synchronized Result tryConsume(Instant now, double refillRate, int maxTokens) {
            // Refill tokens based on elapsed time
            double elapsed = Duration.between(lastRefill, now).toMillis() / 1000.0;
            tokens = Math.min(maxTokens, tokens + elapsed * refillRate);
            lastRefill = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Result.Allowed();
            }

            // Calculate retry-after: time until 1 token is available
            double secondsUntilToken = (1.0 - tokens) / refillRate;
            Duration retryAfter = Duration.ofMillis((long) (secondsUntilToken * 1000) + 1);
            return new Result.Rejected(Optional.of(retryAfter));
        }
    }
}
