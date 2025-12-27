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
 * that can be overridden by user-defined beans.
 */
@Factory
@Requires(property = "durable-streams.enabled", value = "true", defaultValue = "true")
public class DurableStreamsFactory {

    @Singleton
    @Requires(missingBeans = StreamStore.class)
    public StreamStore durableStreamsStore() {
        return new InMemoryStreamStore();
    }

    @Singleton
    @Requires(missingBeans = DurableStreamsHandler.class)
    public DurableStreamsHandler durableStreamsHandler(StreamStore store) {
        return new DurableStreamsHandler(store);
    }
}
