package io.durablestreams.client;

import io.durablestreams.core.Offset;

public record CreateResult(int status, Offset nextOffset) {}