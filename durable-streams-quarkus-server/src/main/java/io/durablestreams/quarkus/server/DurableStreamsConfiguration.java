package io.durablestreams.quarkus.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration properties for Durable Streams Quarkus integration.
 *
 * <p>Configure via application.properties:
 * <pre>
 * durable-streams.enabled=true
 * durable-streams.base-path=/streams
 * </pre>
 */
@ConfigMapping(prefix = "durable-streams")
public interface DurableStreamsConfiguration {

    /**
     * Whether Durable Streams integration is enabled.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Base path for Durable Streams endpoints.
     */
    @WithDefault("/streams")
    String basePath();
}
