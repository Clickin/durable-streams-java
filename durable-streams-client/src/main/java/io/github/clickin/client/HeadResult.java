package io.github.clickin.client;

import io.github.clickin.core.Offset;

import java.time.Instant;

/**
 * Result of a HEAD operation (metadata retrieval).
 *
 * @param status the HTTP status code
 * @param contentType the Content-Type of the stream
 * @param nextOffset the current next offset (end) of the stream
 * @param ttlSeconds the stream's TTL in seconds (optional)
 * @param expiresAt the stream's absolute expiration time (optional)
 */
public record HeadResult(int status, String contentType, Offset nextOffset, Long ttlSeconds, Instant expiresAt) {}
