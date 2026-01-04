package io.durablestreams.client;

import io.durablestreams.core.Offset;

import java.net.URI;
import java.util.Objects;

/**
 * Request to read from a stream.
 *
 * @param streamUrl the absolute URL of the stream
 * @param offset the offset to start reading from (may be null, meaning from beginning or as defined by context)
 * @param ifNoneMatch optional ETag for conditional GET (returns 304 if matched)
 */
public record ReadRequest(URI streamUrl, Offset offset, String ifNoneMatch) {
    public ReadRequest {
        Objects.requireNonNull(streamUrl, "streamUrl");
    }
}
