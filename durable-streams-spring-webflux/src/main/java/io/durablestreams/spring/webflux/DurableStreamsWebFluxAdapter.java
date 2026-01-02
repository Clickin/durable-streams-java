package io.durablestreams.spring.webflux;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ResponseBody;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
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
        HttpMethod method = HttpMethod.valueOf(req.method().name());
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
            return builder.body(BodyInserters.fromResource(openRegionResource(region.region())));
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

    private static InputStreamResource openRegionResource(io.durablestreams.server.spi.ReadOutcome.FileRegion region) {
        try {
            FileChannel channel = FileChannel.open(region.path(), StandardOpenOption.READ);
            channel.position(region.position());
            InputStream baseStream = Channels.newInputStream(channel);
            InputStream in = new FilterInputStream(baseStream) {
                private long remaining = region.length();

                @Override
                public int read() throws IOException {
                    if (remaining <= 0) return -1;
                    int value = super.read();
                    if (value >= 0) remaining--;
                    return value;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int toRead = (int) Math.min(len, remaining);
                    int read = super.read(b, off, toRead);
                    if (read > 0) remaining -= read;
                    return read;
                }

                @Override
                public void close() throws IOException {
                    super.close();
                    channel.close();
                }
            };
            return new InputStreamResource(in);
        } catch (IOException e) {
            return new InputStreamResource(InputStream.nullInputStream());
        }
    }
}
