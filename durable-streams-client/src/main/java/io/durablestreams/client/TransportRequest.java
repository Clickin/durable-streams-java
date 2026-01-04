package io.durablestreams.client;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * A generic transport request.
 *
 * @param method the HTTP method (GET, POST, etc.)
 * @param url the target URL
 * @param headers the request headers (may be null in constructor, normalized to empty map)
 * @param body the request body bytes (optional, may be null)
 * @param timeout the request timeout (optional, may be null)
 */
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
