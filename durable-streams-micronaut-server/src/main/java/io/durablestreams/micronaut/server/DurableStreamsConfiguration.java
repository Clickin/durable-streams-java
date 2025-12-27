package io.durablestreams.micronaut.server;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration properties for Durable Streams Micronaut integration.
 *
 * <p>Configure via application properties:
 * <pre>
 * durable-streams.enabled=true
 * durable-streams.base-path=/streams
 * </pre>
 */
@ConfigurationProperties("durable-streams")
public class DurableStreamsConfiguration {

    private boolean enabled = true;
    private String basePath = "/streams";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
