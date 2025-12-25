package io.durablestreams.client.reactor;

import io.durablestreams.client.jdk.DurableStreamsClient;
import io.durablestreams.core.Offset;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.reactive.FlowInterop;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;

/**
 * Reactor adapter over {@link DurableStreamsClient}.
 */
public final class ReactorDurableStreamsClient {

    private final DurableStreamsClient delegate;

    public ReactorDurableStreamsClient(DurableStreamsClient delegate) {
        this.delegate = delegate;
    }

    public DurableStreamsClient delegate() {
        return delegate;
    }

    public Flux<StreamEvent> liveLongPoll(URI streamUrl, Offset offset, String cursor, Duration timeout) {
        return Flux.from(FlowInterop.toReactiveStreamsTyped(delegate.liveLongPoll(streamUrl, offset, cursor, timeout)));
    }

    public Flux<StreamEvent> liveSse(URI streamUrl, Offset offset) {
        return Flux.from(FlowInterop.toReactiveStreamsTyped(delegate.liveSse(streamUrl, offset)));
    }
}
