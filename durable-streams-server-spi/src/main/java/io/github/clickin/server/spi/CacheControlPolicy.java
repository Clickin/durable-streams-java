package io.github.clickin.server.spi;

/**
 * Cache-Control policy values as enum for consistency and type safety.
 *
 * <p>Provides predefined policies matching protocol recommendations
 * while allowing custom implementations via CachePolicy interface.
 */
public enum CacheControlPolicy {
    
    /**
     * Default policy for catch-up reads: public, cacheable.
     * Matches protocol recommendation in PROTOCOL-SUMMARY.md
     */
    PUBLIC_CATCH_UP("public, max-age=60, stale-while-revalidate=300"),
    
    /**
     * Private cache policy for sensitive or personalized data.
     */
    PRIVATE_NO_STORE("private, max-age=60, stale-while-revalidate=300"),
    
    /**
     * No-store policy for dynamic responses.
     */
    NO_STORE("no-store");
    
    private final String value;
    
    CacheControlPolicy(String value) {
        this.value = value;
    }
    
    /**
     * Returns the Cache-Control header value.
     */
    public String value() {
        return value;
    }
}