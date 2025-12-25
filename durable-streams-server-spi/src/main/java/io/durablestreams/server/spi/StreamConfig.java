package io.durablestreams.server.spi;

import io.durablestreams.core.StreamConfig;

/**
 * Server-side stream configuration wrapper.
 *
 * <p>Delegates to core {@link StreamConfig} to maintain consistency
 * between client and server modules.
 */
public final class StreamConfig {
    private final io.durablestreams.core.StreamConfig delegate;

    public StreamConfig(String contentType, Long ttlSeconds, Instant expiresAt) {
        this.delegate = new io.durablestreams.core.StreamConfig(contentType, ttlSeconds, expiresAt);
    }

    /**
     * @return wrapped core configuration
     */
    public io.durablestreams.core.StreamConfig unwrap() {
        return delegate;
    }

    /**
     * Content-Type of the stream.
     */
    public String contentType() {
        return delegate.contentType();
    }

    /**
     * Optional time-to-live in seconds.
     */
    public java.util.Optional<Long> ttlSeconds() {
        return delegate.ttlSeconds();
    }

    /**
     * Optional absolute expiration timestamp.
     */
    public java.util.Optional<Instant> expiresAt() {
        return delegate.expiresAt();
    }
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
