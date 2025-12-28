package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of a read (GET) operation in catch-up or live modes.
 */
public final class ReadOutcome {

    public enum Status {
        OK,
        NOT_FOUND,
        GONE,
        BAD_REQUEST
    }

    private final Status status;
    private final byte[] body;
    private final String contentType;
    private final Offset nextOffset;
    private final boolean upToDate;
    private final String etag;
    private final Optional<String> streamCursor;

    public ReadOutcome(
            Status status,
            byte[] body,
            String contentType,
            Offset nextOffset,
            boolean upToDate,
            String etag,
            String streamCursor
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.body = body;
        this.contentType = contentType;
        this.nextOffset = nextOffset;
        this.upToDate = upToDate;
        this.etag = etag;
        this.streamCursor = Optional.ofNullable(streamCursor);
    }

    public Status status() {
        return status;
    }

    public byte[] body() {
        return body;
    }

    public String contentType() {
        return contentType;
    }

    public Offset nextOffset() {
        return nextOffset;
    }

    public boolean upToDate() {
        return upToDate;
    }

    public String etag() {
        return etag;
    }

    public Optional<String> streamCursor() {
        return streamCursor;
    }
}
