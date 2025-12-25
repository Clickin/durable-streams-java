package io.durablestreams.client.reactor;

import io.durablestreams.client.jdk.DurableStreamsClient;
import io.durablestreams.client.jdk.LiveLongPollRequest;
import io.durablestreams.client.jdk.LiveSseRequest;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.reactive.FlowInterop;
import reactor.core.publisher.Flux;

public final class ReactorDurableStreamsClient {

    private final DurableStreamsClient delegate;

    public ReactorDurableStreamsClient(DurableStreamsClient delegate) {
        this.delegate = delegate;
    }

    public DurableStreamsClient delegate() {
        return delegate;
    }

    public Flux<StreamEvent> subscribeLongPoll(LiveLongPollRequest request) {
        return Flux.from(FlowInterop.toReactiveStreamsTyped(delegate.subscribeLongPoll(request)));
    }

    public Flux<StreamEvent> subscribeSse(LiveSseRequest request) {
        return Flux.from(FlowInterop.toReactiveStreamsTyped(delegate.subscribeSse(request)));
    }
}