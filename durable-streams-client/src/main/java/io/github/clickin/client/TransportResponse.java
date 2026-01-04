package io.github.clickin.client;

import java.util.Map;

/**
 * A generic transport response.
 *
 * @param status the HTTP status code
 * @param headers the response headers (normalized to empty map if null)
 * @param body the response body (type T depends on the call)
 * @param <T> the type of the body
 */
public record TransportResponse<T>(int status, Map<String, ? extends Iterable<String>> headers, T body) {
    public TransportResponse {
        if (headers == null) {
            headers = Map.<String, Iterable<String>>of();
        }
    }
}
