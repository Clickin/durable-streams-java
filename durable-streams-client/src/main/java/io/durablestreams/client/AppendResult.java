package io.durablestreams.client;

import io.durablestreams.core.Offset;

public record AppendResult(int status, Offset nextOffset) {}