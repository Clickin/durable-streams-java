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
 * that can be overridden by user-defined beans.
 */
@ApplicationScoped
public class DurableStreamsProducer {

    @Produces
    @ApplicationScoped
    @DefaultBean
    public StreamStore durableStreamsStore() {
        return new InMemoryStreamStore();
    }

    @Produces
    @ApplicationScoped
    @DefaultBean
    public DurableStreamsHandler durableStreamsHandler(StreamStore store) {
        return new DurableStreamsHandler(store);
    }
}
