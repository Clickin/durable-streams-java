package io.github.clickin.server.core;

import io.github.clickin.core.Offset;
import io.github.clickin.server.spi.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public final class NioFileStreamStore implements AsyncStreamStore, AutoCloseable {

    private final BlockingFileStreamStore blockingStore;
    private final ExecutorService executor;

    public NioFileStreamStore(java.nio.file.Path baseDir) {
        this(new BlockingFileStreamStore(baseDir), VirtualThreads.newExecutor("durable-streams-io"));
    }

    public NioFileStreamStore(java.nio.file.Path baseDir, java.time.Clock clock) {
        this(new BlockingFileStreamStore(baseDir, clock), VirtualThreads.newExecutor("durable-streams-io"));
    }

    public NioFileStreamStore(java.nio.file.Path baseDir, ExecutorService executor) {
        this(new BlockingFileStreamStore(baseDir), executor);
    }

    public NioFileStreamStore(BlockingFileStreamStore blockingStore, ExecutorService executor) {
        this.blockingStore = Objects.requireNonNull(blockingStore, "blockingStore");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletableFuture<CreateOutcome> create(URI url, StreamConfig config, ByteBuffer initialBody) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return blockingStore.create(url, config, toInputStream(initialBody));
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<AppendOutcome> append(URI url, String contentType, String streamSeq, ByteBuffer body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return blockingStore.append(url, contentType, streamSeq, toInputStream(body));
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> delete(URI url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return blockingStore.delete(url);
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<StreamMetadata>> head(URI url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return blockingStore.head(url);
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<ReadOutcome> read(URI url, Offset startOffset, int maxBytesOrMessages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return blockingStore.read(url, startOffset, maxBytesOrMessages);
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> await(URI url, Offset startOffset, Duration timeout) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return blockingStore.await(url, startOffset, timeout);
            } catch (Exception e) {
                throw wrapException(e);
            }
        }, executor);
    }

    @Override
    public void close() throws Exception {
        blockingStore.close();
        executor.shutdown();
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

    public static final class AsyncStorageException extends RuntimeException {
        public AsyncStorageException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
