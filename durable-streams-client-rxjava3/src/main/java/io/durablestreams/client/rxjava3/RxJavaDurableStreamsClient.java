package io.durablestreams.client.rxjava3;

import io.durablestreams.client.jdk.DurableStreamsClient;
import io.durablestreams.core.Offset;
import io.durablestreams.core.StreamEvent;
import io.durablestreams.reactive.FlowInterop;
import io.reactivex.rxjava3.core.Flowable;

import java.net.URI;
import java.time.Duration;

/**
 * RxJava3 adapter over {@link DurableStreamsClient}.
 */
public final class RxJavaDurableStreamsClient {

    private final DurableStreamsClient delegate;

    public RxJavaDurableStreamsClient(DurableStreamsClient delegate) {
        this.delegate = delegate;
    }

    public DurableStreamsClient delegate() {
        return delegate;
    }

    public Flowable<StreamEvent> liveLongPoll(URI streamUrl, Offset offset, String cursor, Duration timeout) {
        return Flowable.fromPublisher((org.reactivestreams.Publisher<StreamEvent>)
                FlowInterop.toReactiveStreamsTyped(delegate.liveLongPoll(streamUrl, offset, cursor, timeout)));
    }

    public Flowable<StreamEvent> liveSse(URI streamUrl, Offset offset) {
        return Flowable.fromPublisher((org.reactivestreams.Publisher<StreamEvent>)
                FlowInterop.toReactiveStreamsTyped(delegate.liveSse(streamUrl, offset)));
    }
}
