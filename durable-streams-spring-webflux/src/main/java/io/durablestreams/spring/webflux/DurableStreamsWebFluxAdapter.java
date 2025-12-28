package io.durablestreams.spring.webflux;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.SseFrame;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

public final class DurableStreamsWebFluxAdapter {
    private final DurableStreamsHandler handler;

    public DurableStreamsWebFluxAdapter(DurableStreamsHandler handler) {
        this.handler = handler;
    }

    public Mono<ServerResponse> handle(ServerRequest req) {
        return req.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> Mono.fromCallable(() -> toEngineRequest(req, body))
                        .map(handler::handle)
                        .flatMap(this::toWebResponse));
    }

    private static io.durablestreams.server.core.ServerRequest toEngineRequest(ServerRequest req, byte[] body) {
        HttpMethod method = HttpMethod.valueOf(req.methodName());
        Map<String, List<String>> headers = new LinkedHashMap<>();
        req.headers().asHttpHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
        ByteArrayInputStream in = (body == null || body.length == 0) ? null : new ByteArrayInputStream(body);
        return new io.durablestreams.server.core.ServerRequest(method, req.uri(), headers, in);
    }

    private Mono<ServerResponse> toWebResponse(io.durablestreams.server.core.ServerResponse response) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(response.status());
        response.headers().forEach((k, v) -> v.forEach(value -> builder.header(k, value)));

        ResponseBody body = response.body();
        if (body instanceof ResponseBody.Empty) {
            return builder.build();
        }
        if (body instanceof ResponseBody.Bytes bytes) {
            return builder.bodyValue(bytes.bytes());
        }
        if (body instanceof ResponseBody.Sse sse) {
            Flux<String> stream = fluxFrom(sse.publisher()).map(SseFrame::render);
            return builder.contentType(MediaType.TEXT_EVENT_STREAM).body(stream, String.class);
        }
        return builder.build();
    }

    private static <T> Flux<T> fluxFrom(Flow.Publisher<T> publisher) {
        return Flux.create(sink -> publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                sink.onCancel(subscription::cancel);
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(T item) {
                sink.next(item);
            }

            @Override
            public void onError(Throwable throwable) {
                sink.error(throwable);
            }

            @Override
            public void onComplete() {
                sink.complete();
            }
        }));
    }
}
