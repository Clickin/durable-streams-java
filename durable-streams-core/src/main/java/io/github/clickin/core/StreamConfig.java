package io.github.clickin.core;

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

    /**
     * Creates a new stream configuration.
     *
     * @param contentType the MIME type of the stream content (e.g. "application/json")
     * @param ttlSeconds the time-to-live in seconds (optional, may be null)
     * @param expiresAt the absolute expiration time (optional, may be null)
     */
    public StreamConfig(String contentType, Long ttlSeconds, Instant expiresAt) {
        this.contentType = contentType;
        this.ttlSeconds = ttlSeconds;
        this.expiresAt = expiresAt;
    }

    /**
     * Content-Type of the stream (e.g., "application/json").
     *
     * @return the content type string
     */
    public String contentType() {
        return contentType;
    }

    /**
     * Optional time-to-live in seconds.
     *
     * @return the TTL in seconds, or {@code null} if not set
     */
    public Long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * Optional absolute expiration timestamp.
     *
     * @return the expiration instant, or {@code null} if not set
     */
    public Instant expiresAt() {
        return expiresAt;
    }
}