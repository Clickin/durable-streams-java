package io.durablestreams.client;

import io.durablestreams.core.Offset;

/**
 * Result of a read operation.
 *
 * @param status the HTTP status code (e.g. 200, 304, 404)
 * @param body the response body bytes
 * @param contentType the Content-Type header of the response
 * @param nextOffset the value of the Stream-Next-Offset header
 * @param upToDate true if the Stream-Up-To-Date header was "true"
 * @param etag the ETag of the response
 */
public record ReadResult(int status, byte[] body, String contentType, Offset nextOffset, boolean upToDate, String etag) {}
