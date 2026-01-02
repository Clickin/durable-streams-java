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
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;

public final class DurableStreamsQuarkusAdapter {
    private DurableStreamsQuarkusAdapter() {
    }

    public static Response handle(HttpMethod method, URI uri, HttpHeaders headers, byte[] body, DurableStreamsHandler handler) {
        ServerResponse response = handler.handle(toEngineRequest(method, uri, headers, body));
        return toJaxRsResponse(response);
    }

    public static Multi<OutboundSseEvent> sse(URI uri, HttpHeaders headers, DurableStreamsHandler handler, Sse sse) {
        ServerResponse response = handler.handle(toEngineRequest(HttpMethod.GET, uri, headers, null));
        if (response.body() instanceof ResponseBody.Sse sseBody) {
            return Multi.createFrom().publisher(sseBody.publisher())
                    .map(frame -> sse.newEventBuilder()
                            .name(frame.event())
                            .data(String.class, frame.data())
                            .build());
        }
        return Multi.createFrom().empty();
    }

    private static ServerRequest toEngineRequest(HttpMethod method, URI uri, HttpHeaders headers, byte[] body) {
        Map<String, List<String>> hdrs = new LinkedHashMap<>(headers.getRequestHeaders());
        ByteArrayInputStream in = (body == null || body.length == 0) ? null : new ByteArrayInputStream(body);
        return new ServerRequest(method, uri, hdrs, in);
    }

    private static Response toJaxRsResponse(ServerResponse response) {
        Response.ResponseBuilder builder = Response.status(response.status());

        response.headers().forEach((k, v) -> {
            if (k == null || v == null || v.isEmpty()) {
                return;
            }
            if ("Content-Type".equalsIgnoreCase(k)) {
                builder.header(k, v.get(0));
            } else {
                v.forEach(value -> builder.header(k, value));
            }
        });

        ResponseBody body = response.body();
        if (body instanceof ResponseBody.Empty) {
            return builder.build();
        }
        if (body instanceof ResponseBody.Bytes bytes) {
            return builder.entity(bytes.bytes()).build();
        }
        if (body instanceof ResponseBody.FileRegion region) {
            return builder.entity(openRegionStream(region.region())).build();
        }
        if (body instanceof ResponseBody.Sse sseBody) {
            return builder.entity(toSseStream(sseBody.publisher())).build();
        }
        return builder.build();
    }

    private static StreamingOutput toSseStream(Flow.Publisher<SseFrame> publisher) {
        return output -> streamSse(output, publisher);
    }

    private static void streamSse(java.io.OutputStream output, Flow.Publisher<SseFrame> publisher) {
        CountDownLatch done = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(SseFrame item) {
                try {
                    output.write(item.render().getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (java.io.IOException e) {
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    done.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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