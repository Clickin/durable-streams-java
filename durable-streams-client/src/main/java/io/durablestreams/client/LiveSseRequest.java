package io.durablestreams.client;

import io.durablestreams.core.Offset;

import java.net.URI;
import java.util.Objects;

/**
 * Request to start a live SSE (Server-Sent Events) subscription.
 *
 * @param streamUrl the absolute URL of the stream
 * @param offset the offset to start reading from
 */
public record LiveSseRequest(URI streamUrl, Offset offset) {
    public LiveSseRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(offset, "offset");
    }
}
