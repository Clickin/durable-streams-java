package io.durablestreams.client;

import io.durablestreams.core.Offset;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Request to start a live Long-Poll subscription.
 *
 * @param streamUrl the absolute URL of the stream
 * @param offset the offset to start reading from
 * @param cursor the stream cursor (from a previous response) for continuity (optional, may be null)
 * @param timeout the long-poll timeout duration (optional, may be null)
 */
public record LiveLongPollRequest(URI streamUrl, Offset offset, String cursor, Duration timeout) {
    public LiveLongPollRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(offset, "offset");
    }
}
