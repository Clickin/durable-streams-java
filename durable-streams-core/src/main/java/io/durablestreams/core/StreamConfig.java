package io.durablestreams.core;

import java.time.Instant;

/**
 * Stream configuration as defined at creation time.
 *
 * <p>Immutable configuration for Content-Type, TTL, and expiration.
 */
public final class StreamConfig {
    private final String contentType;
    private final Long ttlSeconds;
    private final Instant expiresAt;

    public StreamConfig(String contentType, Long ttlSeconds, Instant expiresAt) {
        this.contentType = contentType;
        this.ttlSeconds = ttlSeconds;
        this.expiresAt = expiresAt;
    }

    /**
     * Content-Type of the stream (e.g., "application/json").
     */
    public String contentType() {
        return contentType;
    }

    /**
     * Optional time-to-live in seconds.
     */
    public Long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * Optional absolute expiration timestamp.
     */
    public Instant expiresAt() {
        return expiresAt;
    }
}