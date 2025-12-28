package io.durablestreams.client;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

public record CreateRequest(URI streamUrl, String contentType, Map<String, String> headers, InputStream initialBody) {
    public CreateRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(contentType, "contentType");
    }
}