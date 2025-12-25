package io.durablestreams.server.core;

import io.durablestreams.server.spi.StreamMetadata;

/**
 * Cache-Control policy for GET responses.
 *
 * <p>The protocol gives guidance (public/private, max-age, stale-while-revalidate) but leaves
 * the choice to implementations. This hook allows integrations to configure behavior.
 */
@FunctionalInterface
public interface CachePolicy {
    String cacheControlFor(StreamMetadata meta);

    static CachePolicy defaultPrivate() {
        return meta -> "private, max-age=60, stale-while-revalidate=300";
    }
}
