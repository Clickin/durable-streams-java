package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.CachePolicy;
import io.durablestreams.server.spi.StreamMetadata;

/**
 * Default Cache-Control policy with public catch-up support.
 *
 * <p>Replaces the default private policy to match PROTOCOL-SUMMARY.md recommendation.
 */
public final class DefaultPublicCachePolicy implements CachePolicy {
    
    @Override
    public String cacheControlFor(StreamMetadata meta) {
        // Recommended by protocol: public, max-age=60, stale-while-revalidate=300
        return "public, max-age=60, stale-while-revalidate=300";
    }
}