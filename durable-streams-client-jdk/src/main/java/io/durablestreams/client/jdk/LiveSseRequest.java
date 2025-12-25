package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;

import java.net.URI;
import java.util.Objects;

public record LiveSseRequest(URI streamUrl, Offset offset) {
    public LiveSseRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(offset, "offset");
    }
}