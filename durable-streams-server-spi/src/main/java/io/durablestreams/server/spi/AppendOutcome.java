package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.util.Objects;

/**
 * Result of an append (POST) operation.
 */
public final class AppendOutcome {

    public enum Status {
        /** Successfully appended. */
        APPENDED,
        /** Stream not found. */
        NOT_FOUND,
        /** Operation not supported by store. */
        NOT_SUPPORTED,
        /** Content-Type mismatch or other conflict. */
        CONFLICT,
        /** Invalid request data. */
        BAD_REQUEST
    }

    private final Status status;
    private final Offset nextOffset;
    private final String message;

    /**
     * Creates a new append outcome.
     *
     * @param status the outcome status
     * @param nextOffset the next offset after append (if successful)
     * @param message an optional error/diagnostic message
     */
    public AppendOutcome(Status status, Offset nextOffset, String message) {
        this.status = Objects.requireNonNull(status, "status");
        this.nextOffset = nextOffset;
        this.message = message;
    }

    public Status status() {
        return status;
    }

    public Offset nextOffset() {
        return nextOffset;
    }

    public String message() {
        return message;
    }
}
