package io.durablestreams.http.spi;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an HTTP request to be sent by an {@link HttpClientAdapter}.
 * This is an immutable value type with a fluent builder API.
 */
public final class HttpClientRequest {

    private final URI uri;
    private final String method;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Duration timeout;

    private HttpClientRequest(URI uri, String method, Map<String, String> headers, byte[] body, Duration timeout) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.method = Objects.requireNonNull(method, "method");
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.body = body;
        this.timeout = timeout;
    }

    public URI uri() { return uri; }
    public String method() { return method; }
    public Map<String, String> headers() { return headers; }
    public byte[] body() { return body; }
    public Duration timeout() { return timeout; }

    public static Builder builder(URI uri, String method) {
        return new Builder(uri, method);
    }

    public static Builder get(URI uri) { return new Builder(uri, "GET"); }
    public static Builder post(URI uri) { return new Builder(uri, "POST"); }
    public static Builder put(URI uri) { return new Builder(uri, "PUT"); }
    public static Builder delete(URI uri) { return new Builder(uri, "DELETE"); }
    public static Builder head(URI uri) { return new Builder(uri, "HEAD"); }

    public static final class Builder {
        private final URI uri;
        private final String method;
        private Map<String, String> headers;
        private byte[] body;
        private Duration timeout;

        private Builder(URI uri, String method) {
            this.uri = uri;
            this.method = method;
        }

        public Builder header(String name, String value) {
            if (headers == null) headers = new LinkedHashMap<>();
            headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                if (this.headers == null) this.headers = new LinkedHashMap<>();
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder body(byte[] body) {
            this.body = body;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public HttpClientRequest build() {
            return new HttpClientRequest(uri, method, headers, body, timeout);
        }
    }
}
