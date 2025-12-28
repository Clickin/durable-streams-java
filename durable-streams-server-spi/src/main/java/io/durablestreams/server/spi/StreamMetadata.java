package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata returned for HEAD and used to drive read/write validation.
 */
public final class StreamMetadata {
    private final String internalStreamId;
    private final StreamConfig config;
    private final Offset nextOffset;
    private final Optional<Long> ttlSecondsRemaining;
    private final Optional<Instant> expiresAt;

    public StreamMetadata(
            String internalStreamId,
            StreamConfig config,
            Offset nextOffset,
            Long ttlSecondsRemaining,
            Instant expiresAt
    ) {
        this.internalStreamId = Objects.requireNonNull(internalStreamId, "internalStreamId");
        this.config = Objects.requireNonNull(config, "config");
        this.nextOffset = Objects.requireNonNull(nextOffset, "nextOffset");
        this.ttlSecondsRemaining = Optional.ofNullable(ttlSecondsRemaining);
        this.expiresAt = Optional.ofNullable(expiresAt);
    }

    public String internalStreamId() {
        return internalStreamId;
    }

    public StreamConfig config() {
        return config;
    }

    public Offset nextOffset() {
        return nextOffset;
    }

    public Optional<Long> ttlSecondsRemaining() {
        return ttlSecondsRemaining;
    }

    public Optional<Instant> expiresAt() {
        return expiresAt;
    }
}
