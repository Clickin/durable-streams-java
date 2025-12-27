package io.durablestreams.micronaut.server;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.StreamStore;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Factory for Durable Streams beans in Micronaut.
 *
 * <p>Provides default implementations of {@link StreamStore} and {@link DurableStreamsHandler}
 * that can be overridden by defining your own beans.
 *
 * <p><strong>Note:</strong> This factory does NOT register any controllers or routes.
 * Per the Durable Streams protocol, "a stream is simply a URL" and servers have complete
 * freedom to organize streams using any URL scheme. You must manually create controllers
 * to expose the handler.
 *
 * <p>Example manual controller:
 * <pre>{@code
 * @Controller("/api/streams")
 * public class MyStreamController {
 *     private final DurableStreamsHandler handler;
 *
 *     public MyStreamController(DurableStreamsHandler handler) {
 *         this.handler = handler;
 *     }
 *
 *     @Get("/{path:.*}")
 *     public HttpResponse<?> get(HttpRequest<byte[]> req, @PathVariable String path) {
 *         // delegate to handler...
 *     }
 *     // ... other methods
 * }
 * }</pre>
 *
 * <p>For multiple handlers, define additional beans:
 * <pre>{@code
 * @Factory
 * public class UserStreamsConfig {
 *
 *     @Singleton
 *     @Named("userStore")
 *     public StreamStore userStore() {
 *         return new InMemoryStreamStore();
 *     }
 *
 *     @Singleton
 *     @Named("userHandler")
 *     public DurableStreamsHandler userHandler(@Named("userStore") StreamStore store) {
 *         return new DurableStreamsHandler(store);
 *     }
 * }
 * }</pre>
 */
@Factory
public class DurableStreamsFactory {

    /**
     * Provides a default in-memory {@link StreamStore}.
     * Override by defining your own StreamStore bean.
     */
    @Singleton
    @Requires(missingBeans = StreamStore.class)
    public StreamStore durableStreamsStore() {
        return new InMemoryStreamStore();
    }

    /**
     * Provides a default {@link DurableStreamsHandler} using the configured store.
     * Override by defining your own DurableStreamsHandler bean.
     */
    @Singleton
    @Requires(missingBeans = DurableStreamsHandler.class)
    public DurableStreamsHandler durableStreamsHandler(StreamStore store) {
        return new DurableStreamsHandler(store);
    }
}
