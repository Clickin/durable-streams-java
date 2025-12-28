package io.durablestreams.client;

import java.util.Map;

public record TransportResponse<T>(int status, Map<String, ? extends Iterable<String>> headers, T body) {
    public TransportResponse {
        if (headers == null) {
            headers = Map.<String, Iterable<String>>of();
        }
    }
}
