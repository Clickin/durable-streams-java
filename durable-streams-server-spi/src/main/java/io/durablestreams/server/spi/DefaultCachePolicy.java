package io.durablestreams.server.spi;

/**
 * Type-safe Cache-Control policy implementation based on predefined policies.
 */
public final class DefaultCachePolicy implements CachePolicy {
    private final CacheControlPolicy policy;
    
    public DefaultCachePolicy(CacheControlPolicy policy) {
        this.policy = policy;
    }
    
    @Override
    public String cacheControlFor(StreamMetadata meta) {
        return switch (policy) {
            case PUBLIC_CATCH_UP -> CacheControlPolicy.PUBLIC_CATCH_UP.value();
            case PRIVATE_NO_STORE -> CacheControlPolicy.PRIVATE_NO_STORE.value();
            case NO_STORE -> CacheControlPolicy.NO_STORE.value();
        };
    }
    
    /**
     * Factory method to create the recommended public policy for catch-up reads.
     */
    public static DefaultCachePolicy publicCatchUp() {
        return new DefaultCachePolicy(CacheControlPolicy.PUBLIC_CATCH_UP);
    }
}