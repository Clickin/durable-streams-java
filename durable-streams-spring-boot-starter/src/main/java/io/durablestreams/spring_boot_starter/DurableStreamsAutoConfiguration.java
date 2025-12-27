package io.durablestreams.spring_boot_starter;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.StreamStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Durable Streams core components.
 *
 * <p>Provides default beans for {@link StreamStore} and {@link DurableStreamsHandler}.
 * These can be overridden by defining your own beans.
 *
 * <p><strong>Note:</strong> This autoconfiguration does NOT register any routes or endpoints.
 * Per the Durable Streams protocol, "a stream is simply a URL" and servers have complete
 * freedom to organize streams using any URL scheme. You must manually configure your
 * endpoints to use the handler.
 *
 * <p>Example manual configuration for Spring WebFlux:
 * <pre>{@code
 * @Bean
 * public RouterFunction<ServerResponse> myStreamRoutes(DurableStreamsHandler handler) {
 *     DurableStreamsWebFluxAdapter adapter = new DurableStreamsWebFluxAdapter(handler);
 *     return RouterFunctions.route(RequestPredicates.path("/api/streams/**"), adapter::handle);
 * }
 * }</pre>
 *
 * <p>You can configure multiple handlers at different paths:
 * <pre>{@code
 * @Bean
 * public RouterFunction<ServerResponse> userStreams(
 *         @Qualifier("userStore") StreamStore userStore) {
 *     DurableStreamsHandler handler = new DurableStreamsHandler(userStore);
 *     DurableStreamsWebFluxAdapter adapter = new DurableStreamsWebFluxAdapter(handler);
 *     return RouterFunctions.route(
 *         RequestPredicates.path("/users/{id}/data/**"), adapter::handle);
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass(DurableStreamsHandler.class)
public class DurableStreamsAutoConfiguration {

    /**
     * Provides a default in-memory {@link StreamStore}.
     * Override by defining your own StreamStore bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public StreamStore durableStreamsStore() {
        return new InMemoryStreamStore();
    }

    /**
     * Provides a default {@link DurableStreamsHandler} using the configured store.
     * Override by defining your own DurableStreamsHandler bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public DurableStreamsHandler durableStreamsHandler(StreamStore store) {
        return new DurableStreamsHandler(store);
    }
}
