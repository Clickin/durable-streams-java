package io.durablestreams.server.core;

import java.util.concurrent.Flow;

/**
 * Framework-neutral response body abstraction.
 */
public sealed interface ResponseBody permits ResponseBody.Empty, ResponseBody.Bytes, ResponseBody.Sse {

    record Empty() implements ResponseBody {}

    record Bytes(byte[] bytes) implements ResponseBody {}

    record Sse(Flow.Publisher<SseFrame> publisher) implements ResponseBody {}
}
