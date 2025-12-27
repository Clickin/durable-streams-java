package io.durablestreams.spring.webflux.starter;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.StreamStore;
import io.durablestreams.spring.webflux.DurableStreamsWebFluxAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Durable Streams with Spring WebFlux.
 *
 * <p>Provides default beans for {@link StreamStore}, {@link DurableStreamsHandler},
 * and {@link DurableStreamsWebFluxAdapter}. These can be overridden by defining your own beans.
 *
 * <p><strong>Note:</strong> This autoconfiguration does NOT register any routes.
 * Per the Durable Streams protocol, "a stream is simply a URL" and servers have complete
 * freedom to organize streams using any URL scheme. You must manually configure your
 * routes to use the adapter.
 *
 * <p>Example manual route configuration:
 * <pre>{@code
 * @Configuration
 * public class StreamRoutesConfig {
 *
 *     @Bean
 *     public RouterFunction<ServerResponse> streamRoutes(DurableStreamsWebFluxAdapter adapter) {
 *         return RouterFunctions.route(
 *             RequestPredicates.path("/api/streams/**"),
 *             adapter::handle
 *         );
 *     }
 *
 *     // Multiple handlers example
 *     @Bean
 *     public RouterFunction<ServerResponse> userStreams(
 *             @Qualifier("userStore") StreamStore userStore) {
 *         DurableStreamsHandler handler = new DurableStreamsHandler(userStore);
 *         DurableStreamsWebFluxAdapter adapter = new DurableStreamsWebFluxAdapter(handler);
 *         return RouterFunctions.route(
 *             RequestPredicates.path("/users/{id}/events/**"),
 *             adapter::handle
 *         );
 *     }
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass({DurableStreamsHandler.class, DurableStreamsWebFluxAdapter.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class DurableStreamsWebFluxAutoConfiguration {

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

    /**
     * Provides a default {@link DurableStreamsWebFluxAdapter} for WebFlux integration.
     * Override by defining your own adapter bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public DurableStreamsWebFluxAdapter durableStreamsWebFluxAdapter(DurableStreamsHandler handler) {
        return new DurableStreamsWebFluxAdapter(handler);
    }
}
