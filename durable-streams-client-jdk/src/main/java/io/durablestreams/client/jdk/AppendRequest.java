package io.durablestreams.client.jdk;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

public record AppendRequest(URI streamUrl, String contentType, Map<String, String> headers, InputStream body) {
    public AppendRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(contentType, "contentType");
    }
}