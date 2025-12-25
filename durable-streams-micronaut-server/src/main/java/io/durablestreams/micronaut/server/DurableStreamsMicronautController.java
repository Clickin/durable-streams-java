package io.durablestreams.micronaut.server;

import io.durablestreams.reactive.FlowInterop;
import io.durablestreams.server.core.*;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.sse.Event;
import org.reactivestreams.Publisher;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Micronaut controller adapter over {@link DurableStreamsHandler}.
 *
 * <p>Routes all HTTP methods under the controller path. You should mount this controller at the desired
 * stream URL namespace.
 */
@Controller("/streams")
public final class DurableStreamsMicronautController {

    private final DurableStreamsHandler engine;

    public DurableStreamsMicronautController(DurableStreamsHandler engine) {
        this.engine = engine;
    }

    @Put(uri="/{path:.*}")
    public HttpResponse<?> put(HttpRequest<byte[]> req, @PathVariable String path) throws Exception {
        return respond(req);
    }

    @Post(uri="/{path:.*}")
    public HttpResponse<?> post(HttpRequest<byte[]> req, @PathVariable String path) throws Exception {
        return respond(req);
    }

    @Delete(uri="/{path:.*}")
    public HttpResponse<?> delete(HttpRequest<byte[]> req, @PathVariable String path) throws Exception {
        return respond(req);
    }

    @Head(uri="/{path:.*}")
    public HttpResponse<?> head(HttpRequest<byte[]> req, @PathVariable String path) throws Exception {
        return respond(req);
    }

    @Get(uri="/{path:.*}")
    public HttpResponse<?> get(HttpRequest<byte[]> req, @PathVariable String path) throws Exception {
        // If engine decides SSE, we must return a Publisher<Event<?>>
        io.durablestreams.server.core.ServerResponse r = engine.handle(toEngineRequest(req));
        if (r.body() instanceof ResponseBody.Sse sse) {
            Publisher<SseFrame> frames = (Publisher<SseFrame>) FlowInterop.toReactiveStreamsTyped(sse.publisher());
            return HttpResponse.status(HttpStatus.valueOf(r.status()))
                    .contentType(MediaType.TEXT_EVENT_STREAM_TYPE)
                    .body(new FramesToMicronautEvents(frames));
        }
        return toMicronautResponse(r);
    }

    private HttpResponse<?> respond(HttpRequest<byte[]> req) throws Exception {
        io.durablestreams.server.core.ServerResponse r = engine.handle(toEngineRequest(req));
        return toMicronautResponse(r);
    }

    private static io.durablestreams.server.core.ServerRequest toEngineRequest(HttpRequest<byte[]> req) {
        HttpMethod m = HttpMethod.valueOf(req.getMethodName());
        URI uri = req.getUri();

        Map<String, List<String>> headers = req.getHeaders().names().stream()
                .collect(Collectors.toMap(n -> n, n -> req.getHeaders().getAll(n)));

        byte[] bytes = req.getBody().orElse(null);
        ByteArrayInputStream body = (bytes == null || bytes.length == 0) ? null : new ByteArrayInputStream(bytes);
        return new io.durablestreams.server.core.ServerRequest(m, uri, headers, body);
    }

    private static HttpResponse<?> toMicronautResponse(io.durablestreams.server.core.ServerResponse r) {
        HttpResponse<?> resp = HttpResponse.status(HttpStatus.valueOf(r.status()));
        r.headers().forEach((k, vals) -> vals.forEach(v -> resp.getHeaders().add(k, v)));

        ResponseBody body = r.body();
        if (body instanceof ResponseBody.Empty) return resp;
        if (body instanceof ResponseBody.Bytes bb) return resp.body(bb.bytes());
        // SSE is handled in GET
        return resp;
    }

    /**
     * Minimal adapter from Publisher<SseFrame> to Publisher<Event<String>> without choosing a reactive library.
     */
    private static final class FramesToMicronautEvents implements Publisher<Event<String>> {
        private final Publisher<SseFrame> frames;

        private FramesToMicronautEvents(Publisher<SseFrame> frames) { this.frames = frames; }

        @Override
        public void subscribe(org.reactivestreams.Subscriber<? super Event<String>> s) {
            frames.subscribe(new org.reactivestreams.Subscriber<>() {
                @Override public void onSubscribe(org.reactivestreams.Subscription sub) { s.onSubscribe(sub); }
                @Override public void onNext(SseFrame f) { s.onNext(Event.of(f.data()).name(f.event())); }
                @Override public void onError(Throwable t) { s.onError(t); }
                @Override public void onComplete() { s.onComplete(); }
            });
        }
    }
}
