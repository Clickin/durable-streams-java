package io.github.clickin.client;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Request to create a new stream.
 *
 * @param streamUrl the URL of the stream to create
 * @param contentType the Content-Type of the initial data (if any)
 * @param headers additional headers to send (optional, may be null)
 * @param initialBody the initial body content (optional, may be null)
 */
public record CreateRequest(URI streamUrl, String contentType, Map<String, String> headers, InputStream initialBody) {
    public CreateRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(contentType, "contentType");
    }
}
