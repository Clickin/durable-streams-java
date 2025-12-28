package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.util.Objects;

/**
 * Result of a create (PUT) operation.
 */
public final class CreateOutcome {
    public enum Status {
        CREATED,
        EXISTS_MATCH,      // idempotent success
        EXISTS_CONFLICT    // configuration mismatch
    }

    private final Status status;
    private final StreamMetadata metadata;
    private final Offset nextOffset;

    public CreateOutcome(Status status, StreamMetadata metadata, Offset nextOffset) {
        this.status = Objects.requireNonNull(status, "status");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.nextOffset = Objects.requireNonNull(nextOffset, "nextOffset");
    }

    public Status status() {
        return status;
    }

    public StreamMetadata metadata() {
        return metadata;
    }

    public Offset nextOffset() {
        return nextOffset;
    }
}
