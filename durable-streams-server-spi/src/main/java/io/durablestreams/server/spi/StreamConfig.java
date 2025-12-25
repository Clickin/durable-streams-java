package io.durablestreams.server.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Stream configuration as defined at creation time (Content-Type, TTL/Expires-At).
 */
public final class StreamConfig {
    private final String contentType;
    private final Optional<Long> ttlSeconds;
    private final Optional<Instant> expiresAt;

    public StreamConfig(String contentType, Long ttlSeconds, Instant expiresAt) {
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.ttlSeconds = Optional.ofNullable(ttlSeconds);
        this.expiresAt = Optional.ofNullable(expiresAt);
    }

    public String contentType() {
        return contentType;
    }

    public Optional<Long> ttlSeconds() {
        return ttlSeconds;
    }

    public Optional<Instant> expiresAt() {
        return expiresAt;
    }
}
