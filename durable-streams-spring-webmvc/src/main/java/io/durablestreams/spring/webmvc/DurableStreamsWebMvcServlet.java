package io.durablestreams.spring.webmvc;

import io.durablestreams.server.core.*;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.concurrent.Flow;

/**
 * Minimal HttpServlet adapter for {@link DurableStreamsHandler}.
 *
 * <p>Notes:
 * <ul>
 *   <li>For SSE responses, this servlet uses async IO and streams rendered SSE frames.</li>
 *   <li>This is a reference adapter; production deployments should tune threading/timeouts.</li>
 * </ul>
 */
public final class DurableStreamsWebMvcServlet extends HttpServlet {

    private final DurableStreamsHandler engine;

    public DurableStreamsWebMvcServlet(DurableStreamsHandler engine) {
        this.engine = engine;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            byte[] bodyBytes = readBody(req);
            io.durablestreams.server.core.ServerRequest engineReq = toEngineRequest(req, bodyBytes);
            io.durablestreams.server.core.ServerResponse engineResp = engine.handle(engineReq);

            // headers + status
            resp.setStatus(engineResp.status());
            engineResp.headers().forEach((k, vals) -> vals.forEach(v -> resp.addHeader(k, v)));

            ResponseBody body = engineResp.body();
            if (body instanceof ResponseBody.Empty) {
                return;
            }
            if (body instanceof ResponseBody.Bytes bb) {
                resp.getOutputStream().write(bb.bytes());
                return;
            }
            if (body instanceof ResponseBody.Sse sse) {
                resp.setContentType("text/event-stream");
                resp.setCharacterEncoding("UTF-8");

                AsyncContext async = req.startAsync();
                async.setTimeout(0);

                Flow.Publisher<SseFrame> pub = sse.publisher();
                pub.subscribe(new Flow.Subscriber<>() {
                    private Flow.Subscription sub;

                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        this.sub = subscription;
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(SseFrame item) {
                        try {
                            resp.getOutputStream().write(item.render().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            resp.getOutputStream().flush();
                        } catch (IOException e) {
                            sub.cancel();
                            async.complete();
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        async.complete();
                    }

                    @Override
                    public void onComplete() {
                        async.complete();
                    }
                });
                return;
            }
        } catch (Exception e) {
            resp.setStatus(500);
        }
    }

    private static io.durablestreams.server.core.ServerRequest toEngineRequest(HttpServletRequest req, byte[] bodyBytes) throws Exception {
        HttpMethod m = HttpMethod.valueOf(req.getMethod());
        URI uri = new URI(req.getRequestURL().toString() + (req.getQueryString() == null ? "" : "?" + req.getQueryString()));

        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, Collections.list(req.getHeaders(name)));
        }

        ByteArrayInputStream body = (bodyBytes == null || bodyBytes.length == 0) ? null : new ByteArrayInputStream(bodyBytes);
        return new io.durablestreams.server.core.ServerRequest(m, uri, headers, body);
    }

    private static byte[] readBody(HttpServletRequest req) throws IOException {
        if (req.getContentLengthLong() == 0) return new byte[0];
        try (InputStream in = req.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }
}
