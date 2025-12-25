package io.durablestreams.spring.webflux;

import io.durablestreams.server.core.*;
import io.durablestreams.reactive.FlowInterop;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * WebFlux functional adapter for {@link DurableStreamsHandler}.
 *
 * <p>Usage:
 * <pre>{@code
 * DurableStreamsHandler engine = new DurableStreamsHandler(store);
 * DurableStreamsWebFluxAdapter adapter = new DurableStreamsWebFluxAdapter(engine);
 * RouterFunction<ServerResponse> routes =
 *   RouterFunctions.route(RequestPredicates.all(), adapter::handle);
 * }</pre>
 */
public final class DurableStreamsWebFluxAdapter {

    private final DurableStreamsHandler engine;

    public DurableStreamsWebFluxAdapter(DurableStreamsHandler engine) {
        this.engine = engine;
    }

    public Mono<ServerResponse> handle(ServerRequest req) {
        return req.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> Mono.fromCallable(() -> {
                    io.durablestreams.server.core.ServerRequest engineReq = toEngineRequest(req, bytes);
                    io.durablestreams.server.core.ServerResponse engineResp = engine.handle(engineReq);
                    return toSpringResponse(engineResp);
                }).flatMap(x -> x));
    }

    private static ServerRequest toEngineRequest(ServerRequest req, byte[] bytes) {
        io.durablestreams.server.core.HttpMethod m = io.durablestreams.server.core.HttpMethod.valueOf(req.methodName());
        URI uri = req.uri();

        Map<String, List<String>> headers = req.headers().asHttpHeaders().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        ByteArrayInputStream body = (bytes == null || bytes.length == 0) ? null : new ByteArrayInputStream(bytes);
        return new io.durablestreams.server.core.ServerRequest(m, uri, headers, body);
    }

    private static Mono<ServerResponse> toSpringResponse(io.durablestreams.server.core.ServerResponse r) {
        ServerResponse.BodyBuilder b = ServerResponse.status(r.status());

        r.headers().forEach((k, vals) -> vals.forEach(v -> b.header(k, v)));

        ResponseBody body = r.body();
        if (body instanceof ResponseBody.Empty) {
            return b.build();
        }
        if (body instanceof ResponseBody.Bytes bb) {
            return b.body(BodyInserters.fromValue(bb.bytes()));
        }
        if (body instanceof ResponseBody.Sse sse) {
            Flux<SseFrame> frames = Flux.from(FlowInterop.toReactiveStreamsTyped(sse.publisher()));
            Flux<ServerSentEvent<String>> events = frames.map(f ->
                    ServerSentEvent.builder(f.data()).event(f.event()).build()
            );
            return b.contentType(MediaType.TEXT_EVENT_STREAM).body(events, ServerSentEvent.class);
        }
        return b.build();
    }
}
