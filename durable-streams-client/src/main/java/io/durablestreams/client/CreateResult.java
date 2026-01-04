package io.durablestreams.client;

import io.durablestreams.core.Offset;

/**
 * Result of a stream creation operation.
 *
 * @param status the HTTP status code (201 if created)
 * @param nextOffset the offset of the created stream (usually beginning)
 */
public record CreateResult(int status, Offset nextOffset) {}
