package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;

import java.net.URI;
import java.util.Objects;

public record ReadRequest(URI streamUrl, Offset offset, String ifNoneMatch) {
    public ReadRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
    }
}