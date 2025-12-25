package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;

public record CreateResult(int status, Offset nextOffset) {}