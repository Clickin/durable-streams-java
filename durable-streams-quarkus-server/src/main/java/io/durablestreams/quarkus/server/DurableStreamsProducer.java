package io.durablestreams.quarkus.server;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.StreamStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * CDI producer for Durable Streams beans in Quarkus.
 *
 * <p>Provides default implementations of {@link StreamStore} and {@link DurableStreamsHandler}
 * that can be overridden by defining your own beans.
 *
 * <p><strong>Note:</strong> This producer does NOT register any resources or routes.
 * Per the Durable Streams protocol, "a stream is simply a URL" and servers have complete
 * freedom to organize streams using any URL scheme. You must manually create JAX-RS resources
 * to expose the handler.
 *
 * <p>Example manual resource:
 * <pre>{@code
 * @Path("/api/streams")
 * @ApplicationScoped
 * public class MyStreamResource {
 *     @Inject
 *     DurableStreamsHandler handler;
 *
 *     @GET
 *     @Path("/{path:.*}")
 *     public Response get(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
 *         // delegate to handler...
 *     }
 *     // ... other methods
 * }
 * }</pre>
 *
 * <p>For multiple handlers, define additional producers:
 * <pre>{@code
 * @ApplicationScoped
 * public class UserStreamsProducer {
 *
 *     @Produces
 *     @ApplicationScoped
 *     @Named("userStore")
 *     public StreamStore userStore() {
 *         return new InMemoryStreamStore();
 *     }
 *
 *     @Produces
 *     @ApplicationScoped
 *     @Named("userHandler")
 *     public DurableStreamsHandler userHandler(@Named("userStore") StreamStore store) {
 *         return new DurableStreamsHandler(store);
 *     }
 * }
 * }</pre>
 */
@ApplicationScoped
public class DurableStreamsProducer {

    /**
     * Provides a default in-memory {@link StreamStore}.
     * Override by defining your own StreamStore bean.
     */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public StreamStore durableStreamsStore() {
        return new InMemoryStreamStore();
    }

    /**
     * Provides a default {@link DurableStreamsHandler} using the configured store.
     * Override by defining your own DurableStreamsHandler bean.
     */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public DurableStreamsHandler durableStreamsHandler(StreamStore store) {
        return new DurableStreamsHandler(store);
    }
}
