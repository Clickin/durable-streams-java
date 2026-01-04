package io.github.clickin.server.core;

import java.util.concurrent.Flow;

/**
 * Framework-neutral response body abstraction.
 *
 * <p>Use sub-interfaces to represent different body types (empty, bytes, file region, SSE).
 */
public sealed interface ResponseBody permits ResponseBody.Empty, ResponseBody.Bytes, ResponseBody.FileRegion, ResponseBody.Sse {

    /** Empty response body. */
    record Empty() implements ResponseBody {}

    /** In-memory byte array response body. */
    record Bytes(byte[] bytes) implements ResponseBody {}

    /** Zero-copy file region response body. */
    record FileRegion(io.github.clickin.server.spi.ReadOutcome.FileRegion region) implements ResponseBody {}

    /** SSE stream response body. */
    record Sse(Flow.Publisher<SseFrame> publisher) implements ResponseBody {}
}

