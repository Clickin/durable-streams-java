package io.durablestreams.quarkus;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.ServerRequest;
import io.durablestreams.server.core.ServerResponse;
import io.durablestreams.server.core.SseFrame;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import org.reactivestreams.Publisher;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

public final class DurableStreamsQuarkusAdapter {
    private DurableStreamsQuarkusAdapter() {
    }

    public static Response handle(HttpMethod method, UriInfo uriInfo, HttpHeaders headers, byte[] body, DurableStreamsHandler handler) {
        ServerResponse response = handler.handle(toEngineRequest(method, uriInfo, headers, body));
        return toJaxRsResponse(response);
    }

    public static Multi<OutboundSseEvent> sse(UriInfo uriInfo, HttpHeaders headers, DurableStreamsHandler handler, Sse sse) {
        ServerResponse response = handler.handle(toEngineRequest(HttpMethod.GET, uriInfo, headers, null));
        if (response.body() instanceof ResponseBody.Sse sseBody) {
            Publisher<SseFrame> publisher = toReactivePublisher(sseBody.publisher());
            return Multi.createFrom().publisher(publisher)
                    .map(frame -> sse.newEventBuilder()
                            .name(frame.event())
                            .data(String.class, frame.data())
                            .build());
        }
        return Multi.createFrom().empty();
    }

    private static ServerRequest toEngineRequest(HttpMethod method, UriInfo uriInfo, HttpHeaders headers, byte[] body) {
        URI uri = uriInfo.getRequestUri();
        Map<String, List<String>> hdrs = new LinkedHashMap<>();
        headers.getRequestHeaders().forEach((k, v) -> hdrs.put(k, v));
        ByteArrayInputStream in = (body == null || body.length == 0) ? null : new ByteArrayInputStream(body);
        return new ServerRequest(method, uri, hdrs, in);
    }

    private static Response toJaxRsResponse(ServerResponse response) {
        Response.ResponseBuilder builder = Response.status(response.status());
        response.headers().forEach((k, v) -> v.forEach(value -> builder.header(k, value)));

        ResponseBody body = response.body();
        if (body instanceof ResponseBody.Empty) {
            return builder.build();
        }
        if (body instanceof ResponseBody.Bytes bytes) {
            return builder.entity(bytes.bytes()).build();
        }
        return builder.build();
    }

    private static <T> Publisher<T> toReactivePublisher(Flow.Publisher<T> publisher) {
        return subscriber -> publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(new org.reactivestreams.Subscription() {
                    @Override
                    public void request(long n) {
                        subscription.request(n);
                    }

                    @Override
                    public void cancel() {
                        subscription.cancel();
                    }
                });
            }

            @Override
            public void onNext(T item) {
                subscriber.onNext(item);
            }

            @Override
            public void onError(Throwable throwable) {
                subscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
    }
}
