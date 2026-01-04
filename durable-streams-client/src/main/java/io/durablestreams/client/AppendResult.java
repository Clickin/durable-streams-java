package io.durablestreams.client;

import io.durablestreams.core.Offset;

/**
 * Result of an append operation.
 *
 * @param status the HTTP status code (200 if successful)
 * @param nextOffset the offset of the next chunk in the stream
 */
public record AppendResult(int status, Offset nextOffset) {}
