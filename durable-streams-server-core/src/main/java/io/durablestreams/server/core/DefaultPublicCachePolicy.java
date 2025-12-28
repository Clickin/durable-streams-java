package io.durablestreams.server.core;

import io.durablestreams.server.spi.StreamMetadata;

public final class DefaultPublicCachePolicy implements CachePolicy {
    @Override
    public String cacheControlFor(StreamMetadata meta) {
        return "public, max-age=60, stale-while-revalidate=300";
    }
}
