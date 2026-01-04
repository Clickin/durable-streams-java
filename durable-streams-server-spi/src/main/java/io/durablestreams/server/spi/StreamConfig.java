package io.durablestreams.server.spi;

import java.time.Instant;
import java.util.Optional;

/**
 * Wrapper around core StreamConfig for SPI usage.
 *
 * <p>Provides accessors for content type, TTL, and expiration.
 */
public final class StreamConfig {
    private final io.durablestreams.core.StreamConfig delegate;

    /**
     * Creates a new stream configuration.
     *
     * @param contentType the MIME type
     * @param ttlSeconds the TTL in seconds (optional)
     * @param expiresAt the absolute expiration (optional)
     */
    public StreamConfig(String contentType, Long ttlSeconds, Instant expiresAt) {
        this.delegate = new io.durablestreams.core.StreamConfig(contentType, ttlSeconds, expiresAt);
    }


    public io.durablestreams.core.StreamConfig unwrap() {
        return delegate;
    }

    public String contentType() {
        return delegate.contentType();
    }

    public Optional<Long> ttlSeconds() {
        return Optional.ofNullable(delegate.ttlSeconds());
    }

    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(delegate.expiresAt());
    }
}
