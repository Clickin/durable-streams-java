package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.util.Objects;

/**
 * Result of an append (POST) operation.
 */
public final class AppendOutcome {

    public enum Status {
        APPENDED,
        NOT_FOUND,
        NOT_SUPPORTED,
        CONFLICT,
        BAD_REQUEST
    }

    private final Status status;
    private final Offset nextOffset;
    private final String message;

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
