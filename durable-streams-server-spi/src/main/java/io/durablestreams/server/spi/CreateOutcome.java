package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.util.Objects;

/**
 * Result of a create (PUT) operation.
 */
public final class CreateOutcome {
    public enum Status {
        /** Successfully created. */
        CREATED,
        /** Stream already exists with matching config (idempotent success). */
        EXISTS_MATCH,
        /** Stream exists but config conflicts. */
        EXISTS_CONFLICT
    }

    private final Status status;
    private final StreamMetadata metadata;
    private final Offset nextOffset;

    /**
     * Creates a new create outcome.
     *
     * @param status the outcome status
     * @param metadata the stream metadata (existing or new)
     * @param nextOffset the next offset of the stream
     */
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
