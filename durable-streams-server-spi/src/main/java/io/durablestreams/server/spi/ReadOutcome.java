package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of a read (GET) operation in catch-up or live modes.
 */
public final class ReadOutcome {

    public enum Status {
        /** Content returned (200) or 304 if up-to-date. */
        OK,
        /** Stream not found (404). */
        NOT_FOUND,
        /** Stream expired or deleted (410). */
        GONE,
        /** Invalid request parameters (400). */
        BAD_REQUEST
    }

    private final Status status;
    private final byte[] body;
    private final String contentType;
    private final Offset nextOffset;
    private final boolean upToDate;
    private final String etag;
    private final Optional<String> streamCursor;
    private final FileRegion fileRegion;

    /**
     * Creates a new read outcome with in-memory body.
     *
     * @param status the status
     * @param body the body bytes (may be null for 304 or empty)
     * @param contentType the content type
     * @param nextOffset the next offset
     * @param upToDate true if reader is up-to-date
     * @param etag the ETag
     * @param streamCursor the optional cursor for live mode
     */
    public ReadOutcome(
            Status status,
            byte[] body,
            String contentType,
            Offset nextOffset,
            boolean upToDate,
            String etag,
            String streamCursor
    ) {
        this(status, body, contentType, nextOffset, upToDate, etag, streamCursor, null);
    }

    /**
     * Creates a new read outcome with optional zero-copy file region.
     *
     * @param status the status
     * @param body the body bytes (may be null)
     * @param contentType the content type
     * @param nextOffset the next offset
     * @param upToDate true if reader is up-to-date
     * @param etag the ETag
     * @param streamCursor the optional cursor
     * @param fileRegion the optional file region for zero-copy transfer
     */
    public ReadOutcome(
            Status status,
            byte[] body,
            String contentType,
            Offset nextOffset,
            boolean upToDate,
            String etag,
            String streamCursor,
            FileRegion fileRegion
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.body = body;
        this.contentType = contentType;
        this.nextOffset = nextOffset;
        this.upToDate = upToDate;
        this.etag = etag;
        this.streamCursor = Optional.ofNullable(streamCursor);
        this.fileRegion = fileRegion;
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

    public Optional<FileRegion> fileRegion() {
        return Optional.ofNullable(fileRegion);
    }

    public record FileRegion(java.nio.file.Path path, long position, int length) {
        public FileRegion {
            Objects.requireNonNull(path, "path");
            if (position < 0) throw new IllegalArgumentException("position must be non-negative");
            if (length < 0) throw new IllegalArgumentException("length must be non-negative");
        }
    }

}
