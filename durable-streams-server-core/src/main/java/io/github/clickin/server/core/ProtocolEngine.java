package io.github.clickin.server.core;

import io.github.clickin.server.spi.CursorPolicy;
import io.github.clickin.server.spi.StreamStore;

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
        this.handler = DurableStreamsHandler.builder(Objects.requireNonNull(store, "store"))
                .cursorPolicy(Objects.requireNonNull(cursorPolicy, "cursorPolicy"))
                .cachePolicy(Objects.requireNonNull(cachePolicy, "cachePolicy"))
                .longPollTimeout(Objects.requireNonNull(longPollTimeout, "longPollTimeout"))
                .sseMaxDuration(Objects.requireNonNull(sseMaxDuration, "sseMaxDuration"))
                .maxChunkSize(maxChunkSize)
                .clock(Objects.requireNonNull(clock, "clock"))
                .build();
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
