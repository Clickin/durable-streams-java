package io.durablestreams.server.core;

import io.durablestreams.server.spi.CursorPolicy;
import io.durablestreams.server.spi.StreamStore;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

public final class ProtocolEngine {
    private final DurableStreamsHandler handler;

    public ProtocolEngine(StreamStore store) {
        this.handler = new DurableStreamsHandler(store);
    }

    public ProtocolEngine(
            StreamStore store,
            CursorPolicy cursorPolicy,
            CachePolicy cachePolicy,
            Duration longPollTimeout,
            Duration sseMaxDuration,
            int maxChunkSize,
            Clock clock
    ) {
        this.handler = new DurableStreamsHandler(
                Objects.requireNonNull(store, "store"),
                Objects.requireNonNull(cursorPolicy, "cursorPolicy"),
                Objects.requireNonNull(cachePolicy, "cachePolicy"),
                Objects.requireNonNull(longPollTimeout, "longPollTimeout"),
                Objects.requireNonNull(sseMaxDuration, "sseMaxDuration"),
                maxChunkSize,
                Objects.requireNonNull(clock, "clock")
        );
    }

    public ServerResponse handle(ServerRequest request) {
        return handler.handle(request);
    }

    public ServerResponse handleCreate(ServerRequest request) {
        return handler.handle(request);
    }

    public ServerResponse handleAppend(ServerRequest request) {
        return handler.handle(request);
    }

    public ServerResponse handleRead(ServerRequest request) {
        return handler.handle(request);
    }

    public ServerResponse handleHead(ServerRequest request) {
        return handler.handle(request);
    }

    public ServerResponse handleDelete(ServerRequest request) {
        return handler.handle(request);
    }

    public ServerResponse handleSse(ServerRequest request) {
        return handler.handle(request);
    }

    public DurableStreamsHandler delegate() {
        return handler;
    }
}
