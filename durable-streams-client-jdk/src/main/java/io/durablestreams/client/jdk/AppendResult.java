package io.durablestreams.client.jdk;

import io.durablestreams.core.Offset;

public record AppendResult(int status, Offset nextOffset) {}