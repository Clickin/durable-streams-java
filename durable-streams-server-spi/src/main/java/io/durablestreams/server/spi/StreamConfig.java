package io.durablestreams.server.spi;

import java.time.Instant;
import java.util.Optional;

public final class StreamConfig {
    private final io.durablestreams.core.StreamConfig delegate;

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
