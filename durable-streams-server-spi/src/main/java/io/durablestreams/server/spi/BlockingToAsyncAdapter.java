package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Adapter that wraps a blocking {@link StreamStore} to provide {@link AsyncStreamStore} interface.
 *
 * <p>This adapter runs all blocking operations on the provided {@link Executor}. For optimal
 * performance with reactive frameworks, use:
 * <ul>
 *   <li>{@code Executors.newVirtualThreadPerTaskExecutor()} (Java 21+) - best for high concurrency</li>
 *   <li>{@code Schedulers.boundedElastic()} (Project Reactor) - for WebFlux applications</li>
 *   <li>A bounded thread pool for controlled resource usage</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * StreamStore blocking = new InMemoryStreamStore();
 * Executor executor = Executors.newVirtualThreadPerTaskExecutor();
 * AsyncStreamStore async = new BlockingToAsyncAdapter(blocking, executor);
 *
 * // Now use async API
 * async.read(url, offset, 1024)
 *      .thenAccept(outcome -> process(outcome));
 * }</pre>
 *
 * <p>Note: While this adapter provides an async interface, the underlying operations still
 * block a thread from the executor pool. For true non-blocking I/O, use implementations
 * like {@code NioFileStreamStore} that use Java NIO channels directly.
 *
 * @see AsyncStreamStore
 * @see StreamStore
 */
public final class BlockingToAsyncAdapter implements AsyncStreamStore {

    private final StreamStore delegate;
    private final Executor executor;

    /**
     * Creates an async adapter for the given blocking store.
     *
     * @param delegate the blocking store to wrap
     * @param executor executor to run blocking operations on
     */
    public BlockingToAsyncAdapter(StreamStore delegate, Executor executor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<CreateOutcome> create(URI url, StreamConfig config, ByteBuffer initialBody) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delegate.create(url, config, toInputStream(initialBody));
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<AppendOutcome> append(URI url, String contentType, String streamSeq, ByteBuffer body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delegate.append(url, contentType, streamSeq, toInputStream(body));
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> delete(URI url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delegate.delete(url);
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<StreamMetadata>> head(URI url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delegate.head(url);
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<ReadOutcome> read(URI url, Offset startOffset, int maxBytesOrMessages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delegate.read(url, startOffset, maxBytesOrMessages);
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> await(URI url, Offset startOffset, Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return delegate.await(url, startOffset, timeout);
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    /**
     * Returns the underlying blocking store.
     */
    public StreamStore delegate() {
        return delegate;
    }

    /**
     * Returns the executor used for async operations.
     */
    public Executor executor() {
        return executor;
    }

    private static ByteArrayInputStream toInputStream(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return null;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new ByteArrayInputStream(bytes);
    }

    private static RuntimeException wrapException(Exception e) {
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new AsyncStorageException(e);
    }

    /**
     * Exception wrapper for checked exceptions from blocking store operations.
     */
    public static final class AsyncStorageException extends RuntimeException {
        public AsyncStorageException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
