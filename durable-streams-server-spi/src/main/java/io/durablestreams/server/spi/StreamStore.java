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
     * @param url stream URL (absolute)
     * @param config requested configuration (Content-Type, TTL, etc.)
     * @param initialBody optional initial content (may be null)
     * @return the outcome of the creation attempt
     * @throws Exception if an error occurs
     */
    CreateOutcome create(URI url, StreamConfig config, InputStream initialBody) throws Exception;

    /**
     * Append to an existing stream.
     *
     * @param url stream URL (absolute)
     * @param contentType request Content-Type (must match the stream's type)
     * @param streamSeq optional monotonic sequence token (for optimistic concurrency)
     * @param body request body (must not be empty)
     * @return the outcome of the append attempt
     * @throws Exception if an error occurs
     */
    AppendOutcome append(URI url, String contentType, String streamSeq, InputStream body) throws Exception;

    /**
     * Delete a stream.
     *
     * @param url the stream URL
     * @return true if deleted; false if not found
     * @throws Exception if an error occurs
     */
    boolean delete(URI url) throws Exception;

    /**
     * Retrieves stream metadata (HEAD).
     *
     * @param url the stream URL
     * @return the metadata if found, or empty if not found
     * @throws Exception if an error occurs
     */
    Optional<StreamMetadata> head(URI url) throws Exception;

    /**
     * Performs a catch-up read (non-live).
     *
     * @param url the stream URL
     * @param startOffset the offset to start reading from
     * @param maxBytesOrMessages hint for the maximum data to return
     * @return the read outcome
     * @throws Exception if an error occurs
     */
    ReadOutcome read(URI url, Offset startOffset, int maxBytesOrMessages) throws Exception;

    /**
     * Await new data becoming available at or beyond the given offset.
     *
     * <p>This method blocks until data is available or the timeout is reached.
     *
     * @param url the stream URL
     * @param startOffset the offset we are interested in
     * @param timeout the maximum time to wait
     * @return true if data became available, false if timeout
     * @throws Exception if an error occurs
     */
    boolean await(URI url, Offset startOffset, Duration timeout) throws Exception;
}
