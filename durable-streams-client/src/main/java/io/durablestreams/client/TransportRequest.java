package io.durablestreams.client;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record TransportRequest(
        String method,
        URI url,
        Map<String, ? extends Iterable<String>> headers,
        byte[] body,
        Duration timeout
) {
    public TransportRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(url, "url");
        if (headers == null) {
            headers = Map.<String, Iterable<String>>of();
        }
    }
}
