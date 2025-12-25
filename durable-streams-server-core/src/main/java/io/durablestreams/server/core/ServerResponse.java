package io.durablestreams.server.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral response abstraction.
 */
public final class ServerResponse {
    private final int status;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final ResponseBody body;

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

    public ServerResponse header(String name, String value) {
        headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }
}
