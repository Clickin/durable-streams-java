package io.durablestreams.client;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Request to append data to an existing stream.
 *
 * @param streamUrl the absolute URL of the stream
 * @param contentType the Content-Type of the data
 * @param headers additional headers to send (optional, may be null)
 * @param body the body content to append
 */
public record AppendRequest(URI streamUrl, String contentType, Map<String, String> headers, InputStream body) {
    public AppendRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
        Objects.requireNonNull(contentType, "contentType");
    }
}
