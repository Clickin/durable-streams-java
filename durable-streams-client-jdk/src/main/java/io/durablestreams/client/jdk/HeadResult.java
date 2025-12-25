package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;

import java.time.Instant;

public record HeadResult(int status, String contentType, Offset nextOffset, Long ttlSeconds, Instant expiresAt) {}