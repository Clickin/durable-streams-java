package io.durablestreams.client;

import io.durablestreams.core.Offset;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record LiveLongPollRequest(URI streamUrl, Offset offset, String cursor, Duration timeout) {
    public LiveLongPollRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(offset, "offset");
    }
}