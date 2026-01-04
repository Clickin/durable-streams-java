package io.github.clickin.server.core;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral request abstraction.
 *
 * <p>Adapters should map their framework-specific request objects to this class.
 */
public final class ServerRequest {
    private final HttpMethod method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final InputStream body; // may be null

    /**
     * Creates a new request.
     *
     * @param method the HTTP method
     * @param uri the full request URI (including query parameters)
     * @param headers the request headers
     * @param body the request body stream (may be null if empty)
     */
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
