package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Storage/service abstraction for Durable Streams.
 *
 * <p>This SPI is intentionally minimal and blocking. Reactive/coroutine behavior is implemented
 * in integration layers by running these calls on appropriate executors/schedulers.
 */
public interface StreamStore {

    /**
     * Create stream if absent.
     *
     * @param url stream URL
     * @param config requested configuration
     * @param initialBody optional initial content (may be null)
     */
    CreateOutcome create(URI url, StreamConfig config, InputStream initialBody) throws Exception;

    /**
     * Append to an existing stream.
     *
     * @param url stream URL
     * @param contentType request Content-Type (must match)
     * @param streamSeq optional monotonic sequence token (may be null)
     * @param body request body (must not be empty)
     */
    AppendOutcome append(URI url, String contentType, String streamSeq, InputStream body) throws Exception;

    /**
     * Delete a stream.
     * @return true if deleted; false if not found
     */
    boolean delete(URI url) throws Exception;

    /**
     * HEAD metadata.
     */
    Optional<StreamMetadata> head(URI url) throws Exception;

    /**
     * Catch-up read (non-live).
     */
    ReadOutcome read(URI url, Offset startOffset, int maxBytesOrMessages) throws Exception;

    /**
     * Await new data becoming available at or beyond the given offset.
     *
     * @return true if data became available, false if timeout
     */
    boolean await(URI url, Offset startOffset, Duration timeout) throws Exception;
}
