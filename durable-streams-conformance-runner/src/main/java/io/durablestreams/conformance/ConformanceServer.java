package io.durablestreams.conformance;

import io.durablestreams.server.core.CachePolicy;
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.core.BlockingFileStreamStore;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.ServerRequest;
import io.durablestreams.server.core.ServerResponse;
import io.durablestreams.server.core.SseFrame;
import io.durablestreams.server.spi.CursorPolicy;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

public final class ConformanceServer {
    private static final int PORT = 4437;

    public static void main(String[] args) {

        DurableStreamsHandler handler = DurableStreamsHandler.builder(new BlockingFileStreamStore(Path.of("", "data")))
                .cursorPolicy(new CursorPolicy(Clock.systemUTC()))
                .cachePolicy(CachePolicy.defaultPrivate())
                .longPollTimeout(Duration.ofSeconds(25))
                .sseMaxDuration(Duration.ofSeconds(60))
                .maxChunkSize(64 * 1024)
                .clock(Clock.systemUTC())
                .build();

        Javalin app = Javalin.create();
        app.get("/*", ctx -> handle(ctx, handler));
        app.post("/*", ctx -> handle(ctx, handler));
        app.put("/*", ctx -> handle(ctx, handler));
        app.delete("/*", ctx -> handle(ctx, handler));
        app.head("/*", ctx -> handle(ctx, handler));
        app.start(PORT);
        System.out.println("Conformance server listening on http://localhost:" + PORT);
    }

    private static void handle(Context ctx, DurableStreamsHandler handler) throws IOException {
        ServerRequest request = new ServerRequest(
                HttpMethod.valueOf(ctx.method().name()),
                URI.create(ctx.fullUrl()),
                toHeaders(ctx),
                bodyOrNull(ctx));

        ServerResponse response = handler.handle(request);
        ctx.status(response.status());
        for (Map.Entry<String, List<String>> e : response.headers().entrySet()) {
            for (String v : e.getValue()) {
                ctx.header(e.getKey(), v);
            }
        }

        if (response.body() instanceof ResponseBody.Empty) {
            return;
        }

        if (response.body() instanceof ResponseBody.Bytes bytes) {
            ctx.result(bytes.bytes());
            return;
        }

        if (response.body() instanceof ResponseBody.FileRegion region) {
            writeFileRegion(ctx, region.region());
            return;
        }

        if (response.body() instanceof ResponseBody.Sse sse) {
            ctx.contentType("text/event-stream");
            writeSse(ctx, sse.publisher());
        }
    }

    private static Map<String, List<String>> toHeaders(Context ctx) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : ctx.headerMap().entrySet()) {
            headers.put(e.getKey(), List.of(e.getValue()));
        }
        return headers;
    }

    private static java.io.InputStream bodyOrNull(Context ctx) {
        long len = ctx.req().getContentLengthLong();
        if (len <= 0)
            return null;
        return ctx.bodyInputStream();
    }

    private static void writeSse(Context ctx, Flow.Publisher<SseFrame> publisher) throws IOException {
        OutputStream out = ctx.res().getOutputStream();
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
                    out.write(item.render().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    subscription.cancel();
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
            done.await(70, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeFileRegion(Context ctx, io.durablestreams.server.spi.ReadOutcome.FileRegion region)
            throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(region.path(), java.nio.file.StandardOpenOption.READ)) {
            channel.transferTo(region.position(), region.length(),
                    java.nio.channels.Channels.newChannel(ctx.res().getOutputStream()));
        }
    }
}
