# durable-streams-java

Java 17 implementation of the Durable Streams protocol.

## Built modules

- `durable-streams-core` - protocol types and helpers
- `durable-streams-client` - JDK HttpClient client
- `durable-streams-json-spi` - JSON serialization SPI (library-agnostic)
- `durable-streams-json-jackson` - Jackson implementation for JSON mode (optional)
- `durable-streams-server-spi` - server storage/policy abstractions
- `durable-streams-server-core` - protocol engine
- `durable-streams-conformance-runner` - conformance server/client runner

## Reference adapters (not built)

These directories are kept as reference examples and are excluded from `settings.gradle.kts`:

- `durable-streams-reactive-adapters`
- `durable-streams-client-reactor`
- `durable-streams-client-rxjava3`
- `durable-streams-kotlin`
- `durable-streams-spring-webflux`
- `durable-streams-spring-webmvc`
- `durable-streams-spring-webflux-starter`
- `durable-streams-spring-webmvc-starter`
- `durable-streams-spring-boot-starter`
- `durable-streams-micronaut-server`
- `durable-streams-micronaut-client`
- `durable-streams-quarkus-server`
- `durable-streams-quarkus-client`

## JSON mode

JSON mode is required by the protocol and implemented via the JSON SPI. You can use the Jackson module or provide your own codec implementation.

- Default codec discovery uses `ServiceLoader` via `StreamCodecProvider`.
- To avoid Jackson, ship your own module that implements `JsonCodec` and `StreamCodecProvider` for `application/json`.

## Client usage

Basic usage:

```java
import io.durablestreams.client.AppendRequest;
import io.durablestreams.client.CreateRequest;
import io.durablestreams.client.DurableStreamsClient;
import io.durablestreams.client.ReadRequest;
import io.durablestreams.core.Offset;

DurableStreamsClient client = DurableStreamsClient.create();

client.create(new CreateRequest(streamUrl, "application/json", null, null));
client.append(new AppendRequest(streamUrl, "application/json", null, dataStream));
var read = client.readCatchUp(new ReadRequest(streamUrl, Offset.beginning(), null));
```

Custom transport (no ServiceLoader, GraalVM-friendly):

```java
import io.durablestreams.client.DurableStreamsClient;
import io.durablestreams.client.DurableStreamsTransport;
import io.durablestreams.client.TransportRequest;
import io.durablestreams.client.TransportResponse;

DurableStreamsTransport transport = new MyHttpTransport();
DurableStreamsClient client = DurableStreamsClient.builder()
        .transport(transport)
        .build();
```

## Server integration examples

These examples wire the protocol handler into common frameworks. They follow the same handler flow used in `durable-streams-conformance-runner`.

### Javalin

```java
import io.durablestreams.server.core.CachePolicy;
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.ServerRequest;
import io.durablestreams.server.core.ServerResponse;
import io.durablestreams.server.core.SseFrame;
import io.durablestreams.server.spi.CursorPolicy;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

DurableStreamsHandler handler = DurableStreamsHandler.builder(new InMemoryStreamStore())
        .cursorPolicy(new CursorPolicy(Clock.systemUTC()))
        .cachePolicy(CachePolicy.defaultPrivate())
        .longPollTimeout(Duration.ofSeconds(25))
        .sseMaxDuration(Duration.ofSeconds(60))
        .build();

Javalin app = Javalin.create();
app.get("/*", ctx -> handle(ctx, handler));
app.post("/*", ctx -> handle(ctx, handler));
app.put("/*", ctx -> handle(ctx, handler));
app.delete("/*", ctx -> handle(ctx, handler));
app.head("/*", ctx -> handle(ctx, handler));
app.start(4437);

static void handle(Context ctx, DurableStreamsHandler handler) throws Exception {
    ServerRequest request = new ServerRequest(
            HttpMethod.valueOf(ctx.method().name()),
            URI.create(ctx.fullUrl()),
            toHeaders(ctx),
            bodyOrNull(ctx)
    );

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
    if (response.body() instanceof ResponseBody.Sse sse) {
        ctx.contentType("text/event-stream");
        writeSse(ctx, sse.publisher());
    }
}

static Map<String, List<String>> toHeaders(Context ctx) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : ctx.headerMap().entrySet()) {
        headers.put(e.getKey(), List.of(e.getValue()));
    }
    return headers;
}

static java.io.InputStream bodyOrNull(Context ctx) {
    long len = ctx.req().getContentLengthLong();
    return len <= 0 ? null : ctx.bodyInputStream();
}

static void writeSse(Context ctx, Flow.Publisher<SseFrame> publisher) throws Exception {
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
            } catch (Exception e) {
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
    done.await(70, TimeUnit.SECONDS);
}
```

### Spring WebFlux

```java
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.SseFrame;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Bean
RouterFunction<ServerResponse> durableStreamsRoutes(DurableStreamsHandler handler) {
    return RouterFunctions.route()
            .add(RouterFunctions.route(req -> true, req -> handle(req, handler)))
            .build();
}

private Mono<ServerResponse> handle(ServerRequest req, DurableStreamsHandler handler) {
    return req.bodyToMono(byte[].class)
            .defaultIfEmpty(new byte[0])
            .flatMap(body -> {
                io.durablestreams.server.core.ServerRequest dsReq = new io.durablestreams.server.core.ServerRequest(
                        HttpMethod.valueOf(req.methodName()),
                        req.uri(),
                        toHeaders(req),
                        body.length == 0 ? null : new java.io.ByteArrayInputStream(body)
                );
                io.durablestreams.server.core.ServerResponse dsResp = handler.handle(dsReq);
                ServerResponse.BodyBuilder builder = ServerResponse.status(dsResp.status());
                dsResp.headers().forEach((k, v) -> v.forEach(value -> builder.header(k, value)));

                if (dsResp.body() instanceof ResponseBody.Empty) {
                    return builder.build();
                }
                if (dsResp.body() instanceof ResponseBody.Bytes bytes) {
                    return builder.bodyValue(bytes.bytes());
                }
                if (dsResp.body() instanceof ResponseBody.Sse sse) {
                    Flux<String> stream = Flux.from(sse.publisher()).map(SseFrame::render);
                    return builder.contentType(MediaType.TEXT_EVENT_STREAM).body(stream, String.class);
                }
                return builder.build();
            });
}

private Map<String, List<String>> toHeaders(ServerRequest req) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    req.headers().asHttpHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
    return headers;
}
```

### Micronaut

```java
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.ServerRequest;
import io.durablestreams.server.core.ServerResponse;
import io.durablestreams.server.core.SseFrame;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

@Controller("/")
final class DurableStreamsController {
    private final DurableStreamsHandler handler;

    DurableStreamsController(DurableStreamsHandler handler) {
        this.handler = handler;
    }

    @Get("/{+path}")
    HttpResponse<?> get(HttpRequest<byte[]> request) {
        return handle(request);
    }

    @Post("/{+path}")
    HttpResponse<?> post(HttpRequest<byte[]> request) {
        return handle(request);
    }

    @Put("/{+path}")
    HttpResponse<?> put(HttpRequest<byte[]> request) {
        return handle(request);
    }

    @Delete("/{+path}")
    HttpResponse<?> delete(HttpRequest<byte[]> request) {
        return handle(request);
    }

    @Head("/{+path}")
    HttpResponse<?> head(HttpRequest<byte[]> request) {
        return handle(request);
    }

    private HttpResponse<?> handle(HttpRequest<byte[]> request) {
        ServerRequest dsReq = new ServerRequest(
                HttpMethod.valueOf(request.getMethod().name()),
                request.getUri(),
                request.getHeaders().asMap(),
                bodyOrNull(request.getBody().orElse(null))
        );
        ServerResponse dsResp = handler.handle(dsReq);
        HttpResponse<?> out = HttpResponse.status(dsResp.status());
        dsResp.headers().forEach((k, v) -> v.forEach(value -> out.header(k, value)));

        if (dsResp.body() instanceof ResponseBody.Empty) {
            return out;
        }
        if (dsResp.body() instanceof ResponseBody.Bytes bytes) {
            return out.body(bytes.bytes());
        }
        if (dsResp.body() instanceof ResponseBody.Sse sse) {
            Publisher<String> stream = Flux.from(sse.publisher()).map(SseFrame::render);
            return out.contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
        }
        return out;
    }

    private java.io.InputStream bodyOrNull(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        return new java.io.ByteArrayInputStream(body);
    }
}
```

### Quarkus (RESTEasy Reactive)

```java
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.ServerRequest;
import io.durablestreams.server.core.ServerResponse;
import io.durablestreams.server.core.SseFrame;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
final class DurableStreamsResource {
    private final DurableStreamsHandler handler;

    DurableStreamsResource(DurableStreamsHandler handler) {
        this.handler = handler;
    }

    @GET
    @Path("{path:.*}")
    public Response get(jakarta.ws.rs.core.UriInfo uriInfo, byte[] body) {
        return handle("GET", uriInfo, body);
    }

    @POST
    @Path("{path:.*}")
    public Response post(jakarta.ws.rs.core.UriInfo uriInfo, byte[] body) {
        return handle("POST", uriInfo, body);
    }

    @PUT
    @Path("{path:.*}")
    public Response put(jakarta.ws.rs.core.UriInfo uriInfo, byte[] body) {
        return handle("PUT", uriInfo, body);
    }

    @DELETE
    @Path("{path:.*}")
    public Response delete(jakarta.ws.rs.core.UriInfo uriInfo, byte[] body) {
        return handle("DELETE", uriInfo, body);
    }

    @HEAD
    @Path("{path:.*}")
    public Response head(jakarta.ws.rs.core.UriInfo uriInfo, byte[] body) {
        return handle("HEAD", uriInfo, body);
    }

    @GET
    @Path("{path:.*}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> sse(jakarta.ws.rs.core.UriInfo uriInfo) {
        ServerRequest dsReq = new ServerRequest(
                HttpMethod.GET,
                uriInfo.getRequestUri(),
                java.util.Map.of(),
                null
        );
        ServerResponse dsResp = handler.handle(dsReq);
        if (dsResp.body() instanceof ResponseBody.Sse sse) {
            return Multi.createFrom().publisher(sse.publisher()).map(SseFrame::render);
        }
        return Multi.createFrom().empty();
    }

    private Response handle(String method, jakarta.ws.rs.core.UriInfo uriInfo, byte[] body) {
        ServerRequest dsReq = new ServerRequest(
                HttpMethod.valueOf(method),
                uriInfo.getRequestUri(),
                java.util.Map.of(),
                body == null || body.length == 0 ? null : new java.io.ByteArrayInputStream(body)
        );
        ServerResponse dsResp = handler.handle(dsReq);
        Response.ResponseBuilder builder = Response.status(dsResp.status());
        dsResp.headers().forEach((k, v) -> v.forEach(value -> builder.header(k, value)));
        if (dsResp.body() instanceof ResponseBody.Bytes bytes) {
            return builder.entity(bytes.bytes()).build();
        }
        return builder.build();
    }
}
```

## Reactive integration examples

### Reactor

Gradle dependencies:

```kotlin
dependencies {
    implementation("org.reactivestreams:reactive-streams-flow-adapters:1.0.2")
    implementation("io.projectreactor:reactor-core:3.7.1")
}
```

Usage:

```java
import io.durablestreams.client.DurableStreamsClient;
import io.durablestreams.client.LiveLongPollRequest;
import io.durablestreams.core.StreamEvent;
import org.reactivestreams.FlowAdapters;
import reactor.core.publisher.Flux;

DurableStreamsClient client = DurableStreamsClient.create();
Flow.Publisher<StreamEvent> pub = client.subscribeLongPoll(request);
Flux<StreamEvent> flux = Flux.from(FlowAdapters.toPublisher(pub));
```

### RxJava3

Gradle dependencies:

```kotlin
dependencies {
    implementation("org.reactivestreams:reactive-streams-flow-adapters:1.0.2")
    implementation("io.reactivex.rxjava3:rxjava:3.1.9")
}
```

Usage:

```java
import io.durablestreams.client.DurableStreamsClient;
import io.durablestreams.core.StreamEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.reactivestreams.FlowAdapters;

DurableStreamsClient client = DurableStreamsClient.create();
Flow.Publisher<StreamEvent> pub = client.subscribeLongPoll(request);
Flowable<StreamEvent> flowable = Flowable.fromPublisher(FlowAdapters.toPublisher(pub));
```

### Kotlin coroutines

Gradle dependencies:

```kotlin
dependencies {
    implementation("org.reactivestreams:reactive-streams-flow-adapters:1.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.9.0")
}
```

Usage:

```kotlin
import io.durablestreams.client.DurableStreamsClient
import io.durablestreams.core.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.FlowAdapters

val client = DurableStreamsClient.create()
val pub = client.subscribeLongPoll(request)
val flow: Flow<StreamEvent> = FlowAdapters.toPublisher(pub).asFlow()
```
