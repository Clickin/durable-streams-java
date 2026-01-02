package io.durablestreams.servlet;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.ServerRequest;
import io.durablestreams.server.core.ServerResponse;
import io.durablestreams.server.core.SseFrame;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

public final class DurableStreamsServlet extends HttpServlet {
    private final DurableStreamsHandler handler;

    public DurableStreamsServlet(DurableStreamsHandler handler) {
        this.handler = handler;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            byte[] bodyBytes = readBody(req);
            ServerRequest engineReq = toEngineRequest(req, bodyBytes);
            ServerResponse engineResp = handler.handle(engineReq);

            resp.setStatus(engineResp.status());
            engineResp.headers().forEach((k, vals) -> vals.forEach(v -> resp.addHeader(k, v)));

            ResponseBody body = engineResp.body();
            if (body instanceof ResponseBody.Empty) {
                return;
            }
            if (body instanceof ResponseBody.Bytes bytes) {
                resp.getOutputStream().write(bytes.bytes());
                return;
            }
            if (body instanceof ResponseBody.FileRegion region) {
                writeFileRegion(resp, region.region());
                return;
            }
            if (body instanceof ResponseBody.Sse sse) {
                resp.setContentType("text/event-stream");

                AsyncContext async = req.startAsync();
                async.setTimeout(0);

                java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);

                Flow.Publisher<SseFrame> pub = sse.publisher();
                pub.subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        this.subscription = subscription;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(SseFrame item) {
                        try {
                            resp.getOutputStream().write(item.render().getBytes(StandardCharsets.UTF_8));
                            resp.getOutputStream().flush();
                        } catch (IOException e) {
                            subscription.cancel();
                            if (completed.compareAndSet(false, true)) {
                                async.complete();
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        if (completed.compareAndSet(false, true)) {
                            async.complete();
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (completed.compareAndSet(false, true)) {
                            async.complete();
                        }
                    }
                });
            }
        } catch (Exception e) {
            resp.setStatus(500);
        }
    }

    private static ServerRequest toEngineRequest(HttpServletRequest req, byte[] bodyBytes) throws Exception {
        HttpMethod method = HttpMethod.valueOf(req.getMethod());
        URI uri = new URI(req.getRequestURL().toString() + (req.getQueryString() == null ? "" : "?" + req.getQueryString()));

        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, Collections.list(req.getHeaders(name)));
        }

        ByteArrayInputStream body = (bodyBytes == null || bodyBytes.length == 0) ? null : new ByteArrayInputStream(bodyBytes);
        return new ServerRequest(method, uri, headers, body);
    }

    private static byte[] readBody(HttpServletRequest req) throws IOException {
        if (req.getContentLengthLong() == 0) {
            return new byte[0];
        }
        try (InputStream in = req.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) >= 0) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    private static void writeFileRegion(HttpServletResponse resp, io.durablestreams.server.spi.ReadOutcome.FileRegion region) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(region.path(), java.nio.file.StandardOpenOption.READ)) {
            channel.transferTo(region.position(), region.length(), java.nio.channels.Channels.newChannel(resp.getOutputStream()));
        }
    }
}
