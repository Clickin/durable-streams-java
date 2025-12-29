package io.durablestreams.micronaut;

import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.ServerRequest;
import io.durablestreams.server.core.ServerResponse;
import io.durablestreams.server.core.SseFrame;
import io.durablestreams.server.core.DurableStreamsHandler;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.sse.Event;
import org.reactivestreams.Publisher;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

public final class DurableStreamsMicronautAdapter {
    private DurableStreamsMicronautAdapter() {
    }

    public static HttpResponse<?> handle(HttpRequest<byte[]> request, DurableStreamsHandler handler) {
        ServerResponse response = handler.handle(toEngineRequest(request));
        MutableHttpResponse<?> out = HttpResponse.status(HttpStatus.valueOf(response.status()));
        response.headers().forEach((k, v) -> v.forEach(value -> out.header(k, value)));

        ResponseBody body = response.body();
        if (body instanceof ResponseBody.Empty) {
            return out;
        }
        if (body instanceof ResponseBody.Bytes bytes) {
            return out.body(bytes.bytes());
        }
        if (body instanceof ResponseBody.FileRegion region) {
            return out.body(openRegionStream(region.region()));
        }
        if (body instanceof ResponseBody.Sse sse) {
            Publisher<Event<String>> events = toEventPublisher(sse.publisher());
            return out.contentType(MediaType.TEXT_EVENT_STREAM_TYPE).body(events);
        }
        return out;
    }

    private static ServerRequest toEngineRequest(HttpRequest<byte[]> request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethodName());
        URI uri = request.getUri();

        Map<String, List<String>> headers = new LinkedHashMap<>();
        request.getHeaders().names().forEach(name -> headers.put(name, request.getHeaders().getAll(name)));

        byte[] bytes = request.getBody().orElse(null);
        ByteArrayInputStream body = (bytes == null || bytes.length == 0) ? null : new ByteArrayInputStream(bytes);
        return new ServerRequest(method, uri, headers, body);
    }

    private static Publisher<Event<String>> toEventPublisher(Flow.Publisher<SseFrame> publisher) {
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
            public void onNext(SseFrame item) {
                subscriber.onNext(Event.of(item.data()).name(item.event()));
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

    private static java.io.InputStream openRegionStream(io.durablestreams.server.spi.ReadOutcome.FileRegion region) {
        try {
            java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(region.path(), java.nio.file.StandardOpenOption.READ);
            channel.position(region.position());
            java.io.InputStream in = java.nio.channels.Channels.newInputStream(channel);
            return new java.io.FilterInputStream(in) {
                private long remaining = region.length();

                @Override
                public int read() throws java.io.IOException {
                    if (remaining <= 0) return -1;
                    int value = super.read();
                    if (value >= 0) remaining--;
                    return value;
                }

                @Override
                public int read(byte[] b, int off, int len) throws java.io.IOException {
                    if (remaining <= 0) return -1;
                    int toRead = (int) Math.min(len, remaining);
                    int read = super.read(b, off, toRead);
                    if (read > 0) remaining -= read;
                    return read;
                }

                @Override
                public void close() throws java.io.IOException {
                    super.close();
                    channel.close();
                }
            };
        } catch (java.io.IOException e) {
            return java.io.InputStream.nullInputStream();
        }
    }
}
