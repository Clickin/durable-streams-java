package io.github.clickin.core;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/**
 * Normalized stream events for Durable Streams clients/servers.
 *
 * <p>In SSE live mode, servers send {@code event: data} and {@code event: control} events.
 * The control event carries the next offset (required) and cursor (optional).
 */
public sealed interface StreamEvent permits StreamEvent.Data, StreamEvent.Control, StreamEvent.UpToDate {

    /**
     * A data payload chunk.
     *
     * @param bytes the raw data bytes
     * @param contentType the MIME type of the data
     */
    record Data(ByteBuffer bytes, String contentType) implements StreamEvent {
        public Data {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(contentType, "contentType");
        }
    }

    /**
     * A control signal (SSE).
     *
     * @param streamNextOffset the next offset to fetch from
     * @param streamCursor the cursor for the next fetch (optional)
     */
    record Control(Offset streamNextOffset, Optional<String> streamCursor) implements StreamEvent {
        public Control {
            Objects.requireNonNull(streamNextOffset, "streamNextOffset");
            streamCursor = (streamCursor == null) ? Optional.empty() : streamCursor;
        }
    }

    /**
     * Indicates the reader is up-to-date at {@code nextOffset}.
     *
     * @param nextOffset the offset where the stream is up-to-date
     */
    record UpToDate(Offset nextOffset) implements StreamEvent {
        public UpToDate {
            Objects.requireNonNull(nextOffset, "nextOffset");
        }
    }
}
