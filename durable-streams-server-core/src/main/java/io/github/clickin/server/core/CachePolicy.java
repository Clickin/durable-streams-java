package io.github.clickin.server.core;

import io.github.clickin.server.spi.StreamMetadata;

/**
 * Cache-Control policy for GET responses.
 *
 * <p>The protocol gives guidance (public/private, max-age, stale-while-revalidate) but leaves
 * the choice to implementations. This hook allows integrations to configure behavior.
 */
@FunctionalInterface
public interface CachePolicy {
    /**
     * Determines the Cache-Control header value for a stream's metadata.
     *
     * @param meta the stream metadata
     * @return the Cache-Control header value (e.g. "private, max-age=60")
     */
    String cacheControlFor(StreamMetadata meta);

    /**
     * Returns a default policy suitable for private deployments ("private, max-age=60, stale-while-revalidate=300").
     *
     * @return the default private policy
     */
    static CachePolicy defaultPrivate() {
        return meta -> "private, max-age=60, stale-while-revalidate=300";
    }
}
