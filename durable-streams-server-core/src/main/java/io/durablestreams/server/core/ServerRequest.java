package io.durablestreams.server.core;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral request abstraction.
 */
public final class ServerRequest {
    private final HttpMethod method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final InputStream body; // may be null

    public ServerRequest(HttpMethod method, URI uri, Map<String, List<String>> headers, InputStream body) {
        this.method = Objects.requireNonNull(method, "method");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.body = body;
    }

    public HttpMethod method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public InputStream body() {
        return body;
    }
}
