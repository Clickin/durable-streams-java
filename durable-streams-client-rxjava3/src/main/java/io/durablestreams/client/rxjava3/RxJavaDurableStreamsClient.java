package io.durablestreams.client.rxjava3;

import io.durablestreams.client.jdk.DurableStreamsClient;
import io.durablestreams.client.jdk.LiveLongPollRequest;
import io.durablestreams.client.jdk.LiveSseRequest;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.reactive.FlowInterop;
import io.reactivex.rxjava3.core.Flowable;

public final class RxJavaDurableStreamsClient {

    private final DurableStreamsClient delegate;

    public RxJavaDurableStreamsClient(DurableStreamsClient delegate) {
        this.delegate = delegate;
    }

    public DurableStreamsClient delegate() {
        return delegate;
    }

    public Flowable<StreamEvent> subscribeLongPoll(LiveLongPollRequest request) {
        return Flowable.fromPublisher((org.reactivestreams.Publisher<StreamEvent>)
                FlowInterop.toReactiveStreamsTyped(delegate.subscribeLongPoll(request)));
    }

    public Flowable<StreamEvent> subscribeSse(LiveSseRequest request) {
        return Flowable.fromPublisher((org.reactivestreams.Publisher<StreamEvent>)
                FlowInterop.toReactiveStreamsTyped(delegate.subscribeSse(request)));
    }
}