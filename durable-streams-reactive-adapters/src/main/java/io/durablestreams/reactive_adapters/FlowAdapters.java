package io.durablestreams.reactive_adapters;

import org.reactivestreams.Publisher;

import java.util.Objects;
import java.util.concurrent.Flow;

public final class FlowAdapters {
    private FlowAdapters() {}

    public static <T> Flow.Publisher<T> toFlow(Publisher<T> publisher) {
        Objects.requireNonNull(publisher, "publisher");
        return org.reactivestreams.FlowAdapters.toFlowPublisher(publisher);
    }

    public static <T> Publisher<T> toReactiveStreams(Flow.Publisher<T> publisher) {
        Objects.requireNonNull(publisher, "publisher");
        return org.reactivestreams.FlowAdapters.toPublisher(publisher);
    }
}
