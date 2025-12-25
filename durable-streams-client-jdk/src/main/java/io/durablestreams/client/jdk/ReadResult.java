package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;

public record ReadResult(int status, byte[] body, String contentType, Offset nextOffset, boolean upToDate, String etag) {}