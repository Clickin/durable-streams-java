package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous storage abstraction for Durable Streams.
 *
 * <p>This is the non-blocking counterpart to {@link StreamStore}. All operations return
 * {@link CompletableFuture} and complete asynchronously without blocking the calling thread.
 *
 * <p>Implementations should use Java NIO ({@link java.nio.channels.AsynchronousFileChannel},
 * {@link java.nio.channels.AsynchronousSocketChannel}) or other async I/O mechanisms for
 * true non-blocking behavior.
 *
 * <p>For adapting a blocking {@link StreamStore} to this interface, use
 * {@link BlockingToAsyncAdapter} which runs blocking operations on a provided executor.
 *
 * @see StreamStore
 * @see BlockingToAsyncAdapter
 */
public interface AsyncStreamStore {

    /**
     * Create stream if absent.
     *
     * @param url stream URL
     * @param config requested configuration
     * @param initialBody optional initial content (may be null or empty)
     * @return future completing with create outcome
     */
    CompletableFuture<CreateOutcome> create(URI url, StreamConfig config, ByteBuffer initialBody);

    /**
     * Append to an existing stream.
     *
     * @param url stream URL
     * @param contentType request Content-Type (must match stream's content type)
     * @param streamSeq optional monotonic sequence token (may be null)
     * @param body request body (must not be empty)
     * @return future completing with append outcome
     */
    CompletableFuture<AppendOutcome> append(URI url, String contentType, String streamSeq, ByteBuffer body);

    /**
     * Delete a stream.
     *
     * @param url stream URL
     * @return future completing with true if deleted, false if not found
     */
    CompletableFuture<Boolean> delete(URI url);

    /**
     * HEAD metadata.
     *
     * @param url stream URL
     * @return future completing with stream metadata if found
     */
    CompletableFuture<Optional<StreamMetadata>> head(URI url);

    /**
     * Catch-up read (non-live).
     *
     * @param url stream URL
     * @param startOffset position to start reading from
     * @param maxBytesOrMessages maximum bytes (byte streams) or messages (JSON) to return
     * @return future completing with read outcome
     */
    CompletableFuture<ReadOutcome> read(URI url, Offset startOffset, int maxBytesOrMessages);

    /**
     * Await new data becoming available at or beyond the given offset.
     *
     * <p>Unlike the blocking {@link StreamStore#await}, this method returns immediately
     * and the returned future completes when:
     * <ul>
     *   <li>Data becomes available (completes with {@code true})</li>
     *   <li>Timeout expires (completes with {@code false})</li>
     *   <li>Stream is deleted or not found (completes with {@code false})</li>
     * </ul>
     *
     * <p>No thread is blocked during the wait period.
     *
     * @param url stream URL
     * @param startOffset position to wait for data at
     * @param timeout maximum time to wait
     * @return future completing with true if data available, false if timeout
     */
    CompletableFuture<Boolean> await(URI url, Offset startOffset, Duration timeout);
}
