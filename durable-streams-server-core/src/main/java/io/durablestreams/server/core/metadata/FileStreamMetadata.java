package io.durablestreams.server.core.metadata;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.StreamConfig;

import java.time.Instant;
import java.util.Objects;

public record FileStreamMetadata(
        String streamId,
        StreamConfig config,
        Offset nextOffset,
        Instant expiresAt
) {
    public FileStreamMetadata {
        Objects.requireNonNull(streamId, "streamId");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(nextOffset, "nextOffset");
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public FileStreamMetadata withNextOffset(Offset newOffset) {
        return new FileStreamMetadata(streamId, config, newOffset, expiresAt);
    }
}
