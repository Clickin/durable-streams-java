package io.github.clickin.server.spi;

import io.github.clickin.core.Offset;

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

    /**
     * Creates a new metadata instance.
     *
     * @param internalStreamId the internal ID of the stream (e.g. file path or DB key)
     * @param config the stream configuration
     * @param nextOffset the current next offset (end)
     * @param ttlSecondsRemaining remaining TTL in seconds (optional)
     * @param expiresAt absolute expiration time (optional)
     */
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
