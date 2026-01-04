package io.github.clickin.server.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral response abstraction.
 *
 * <p>Adapters should map instances of this class to their framework-specific response objects.
 */
public final class ServerResponse {
    private final int status;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final ResponseBody body;

    /**
     * Creates a new response.
     *
     * @param status the HTTP status code
     * @param body the response body
     */
    public ServerResponse(int status, ResponseBody body) {
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public ResponseBody body() {
        return body;
    }

    /**
     * Adds a header to the response.
     *
     * @param name the header name
     * @param value the header value
     * @return this response (for chaining)
     */
    public ServerResponse header(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }
}
