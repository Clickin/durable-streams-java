package io.durablestreams.quarkus_client;

import io.durablestreams.client.jdk.AppendRequest;
import io.durablestreams.client.jdk.AppendResult;
import io.durablestreams.client.jdk.CreateRequest;
import io.durablestreams.client.jdk.CreateResult;
import io.durablestreams.client.jdk.DurableStreamsClient;
import io.durablestreams.client.jdk.HeadResult;
import io.durablestreams.client.jdk.JdkDurableStreamsClient;
import io.durablestreams.client.jdk.LiveLongPollRequest;
import io.durablestreams.client.jdk.LiveSseRequest;
import io.durablestreams.client.jdk.ReadRequest;
import io.durablestreams.client.jdk.ReadResult;
import io.durablestreams.core.StreamEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Objects;
import java.util.concurrent.Flow;

public final class QuarkusDurableStreamsClient implements DurableStreamsClient {
    private final DurableStreamsClient delegate;

    public QuarkusDurableStreamsClient(DurableStreamsClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static QuarkusDurableStreamsClient create() {
        return new QuarkusDurableStreamsClient(DurableStreamsClient.create());
    }

    public static QuarkusDurableStreamsClient create(HttpClient httpClient) {
        return new QuarkusDurableStreamsClient(new JdkDurableStreamsClient(httpClient));
    }

    @Override
    public CreateResult create(CreateRequest request) throws Exception {
        return delegate.create(request);
    }

    @Override
    public AppendResult append(AppendRequest request) throws Exception {
        return delegate.append(request);
    }

    @Override
    public HeadResult head(URI streamUrl) throws Exception {
        return delegate.head(streamUrl);
    }

    @Override
    public void delete(URI streamUrl) throws Exception {
        delegate.delete(streamUrl);
    }

    @Override
    public ReadResult readCatchUp(ReadRequest request) throws Exception {
        return delegate.readCatchUp(request);
    }

    @Override
    public Flow.Publisher<StreamEvent> subscribeLongPoll(LiveLongPollRequest request) {
        return delegate.subscribeLongPoll(request);
    }

    @Override
    public Flow.Publisher<StreamEvent> subscribeSse(LiveSseRequest request) {
        return delegate.subscribeSse(request);
    }
}
