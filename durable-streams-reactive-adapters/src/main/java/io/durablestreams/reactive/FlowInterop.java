package io.durablestreams.reactive;

import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Interop utilities for Java {@link Flow} and Reactive Streams.
 */
public final class FlowInterop {
    private FlowInterop() {}

    public static org.reactivestreams.Publisher<?> toReactiveStreams(Flow.Publisher<?> publisher) {
        Objects.requireNonNull(publisher, "publisher");
        return org.reactivestreams.FlowAdapters.toPublisher(publisher);
    }

    public static <T> org.reactivestreams.Publisher<T> toReactiveStreamsTyped(Flow.Publisher<T> publisher) {
        Objects.requireNonNull(publisher, "publisher");
        return org.reactivestreams.FlowAdapters.toPublisher(publisher);
    }

    public static <T> Flow.Publisher<T> toFlow(org.reactivestreams.Publisher<T> publisher) {
        Objects.requireNonNull(publisher, "publisher");
        return org.reactivestreams.FlowAdapters.toFlowPublisher(publisher);
    }
}
