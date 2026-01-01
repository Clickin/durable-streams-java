package io.durablestreams.spring.webflux;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ResponseBody;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

public final class DurableStreamsWebFluxAdapter {
    private static final DefaultDataBufferFactory DATA_BUFFER_FACTORY = new DefaultDataBufferFactory();

    private final DurableStreamsHandler handler;

    public DurableStreamsWebFluxAdapter(DurableStreamsHandler handler) {
        this.handler = handler;
    }

    public Mono<ServerResponse> handle(ServerRequest req) {
        return req.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> Mono.fromCallable(() -> handler.handle(toEngineRequest(req, body)))
                        .subscribeOn(Schedulers.boundedElastic())
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
        if (body instanceof ResponseBody.FileRegion region) {
            ResourceRegion resourceRegion = new ResourceRegion(new FileSystemResource(region.region().path()), region.region().position(), region.region().length());
            return builder.body(BodyInserters.fromValue(resourceRegion));
        }
        if (body instanceof ResponseBody.Sse sse) {
            Flux<DataBuffer> stream = fluxFrom(sse.publisher())
                    .map(frame -> DATA_BUFFER_FACTORY.wrap(frame.render().getBytes(StandardCharsets.UTF_8)));

            return builder.headers(headers -> {
                headers.set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
                headers.remove(HttpHeaders.CONTENT_LENGTH);
            }).body(stream, DataBuffer.class);
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
