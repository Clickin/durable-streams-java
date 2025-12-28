package io.durablestreams.server.spi;

public final class DefaultCachePolicy {
    private final CacheControlPolicy policy;

    public DefaultCachePolicy(CacheControlPolicy policy) {
        this.policy = policy;
    }

    public String cacheControlFor(StreamMetadata meta) {
        return switch (policy) {
            case PUBLIC_CATCH_UP -> CacheControlPolicy.PUBLIC_CATCH_UP.value();
            case PRIVATE_NO_STORE -> CacheControlPolicy.PRIVATE_NO_STORE.value();
            case NO_STORE -> CacheControlPolicy.NO_STORE.value();
        };
    }

    public static DefaultCachePolicy publicCatchUp() {
        return new DefaultCachePolicy(CacheControlPolicy.PUBLIC_CATCH_UP);
    }
}
