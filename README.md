# durable-streams-java

Java 17+ implementation of the [Durable Streams](https://github.com/durable-streams/durable-streams) protocol.

Passes the durable-streams conformance suite.

> **Note:** While this library can run on Java 17, **JDK 21+ is highly recommended** to leverage Virtual Threads for high-concurrency performance and scalability.

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com/durable-streams/durable-streams-java)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[Korean README (한국어)](README_ko.md)

## Built modules

- `durable-streams-core` - protocol types and helpers
- `durable-streams-client` - JDK HttpClient client
- `durable-streams-json-spi` - JSON serialization SPI (library-agnostic)
- `durable-streams-json-jackson` - Jackson implementation for JSON mode (optional)
- `durable-streams-server-spi` - server storage/policy abstractions
- `durable-streams-server-core` - protocol engine
- `durable-streams-servlet` - Servlet integration helpers (like Spring MVC)
- `durable-streams-spring-webflux` - Spring WebFlux integration helpers
- `durable-streams-micronaut` - Micronaut integration helpers
- `durable-streams-quarkus` - Quarkus integration helpers
- `durable-streams-ktor` - Ktor integration helpers
- `durable-streams-conformance-runner` - conformance server/client runner

## Example applications

These modules expose the full protocol via framework-specific adapters and are used in conformance tests:

- `example-micronaut` (port 4431)
- `example-quarkus` (port 4432)
- `example-spring-webflux` (port 4433)
- `example-spring-webmvc` (port 4434)
- `example-ktor` (port 4435)

## Key Features & Architecture

- **High-Performance File Storage**: Uses synchronous `FileChannel` I/O on Virtual Threads (Java 21+) with a bounded dedicated I/O executor to prevent platform thread starvation.
- **Strict Concurrency**: Per-stream `ReentrantLock` ensures atomic appends and metadata updates.
- **Efficient Waiting**: Lock-free await queues (`ConcurrentLinkedQueue`) for thousands of concurrent long-poll/SSE connections.
- **Protocol Conformance**: Fully compliant with the Durable Streams protocol (131/131 tests passed), including:
    - Strict byte-offset tracking
    - Streaming JSON parsing (Jackson-default) for low memory footprint
    - Correct ETag generation and cache control
    - Proper handling of `Stream-Seq` for writer coordination

## Performance: Sync vs Async Storage

Early in development, we benchmarked three storage approaches:
1. **Blocking I/O** - Synchronous `FileChannel` (baseline)
2. **NIO Async** - `AsynchronousFileChannel` with callbacks
3. **Virtual Threads** - Blocking I/O wrapped with virtual thread executor (current implementation)

### Benchmark Results

| Workload | Blocking | NIO Async | Virtual Threads (Winner) |
|----------|----------|-----------|--------------------------|
| Sequential writes | Baseline | Slower (callback overhead) | Similar to baseline |
| Sequential reads | Baseline | Slower (callback overhead) | Similar to baseline |
| Concurrent reads | Baseline | **1.08x faster** | **1.33x faster** ⭐ |
| Mixed (70% read, 30% write) | Baseline | Equivalent | Equivalent |
| Await latency | ~2.4ms | ~2.4ms | ~2.4ms |

### Key Findings

- **Virtual Threads won** for concurrent read-heavy workloads (1.33x faster)
- NIO async showed callback overhead in sequential operations
- All implementations had similar await latency (~2.4ms)
- Virtual threads provide the best balance of performance and code simplicity

### Why Virtual Threads + Blocking I/O?

**Critical insight**: Java's `AsynchronousFileChannel` is **not truly asynchronous**. It uses an internal thread pool to emulate async behavior because most operating systems (pre-io_uring on Linux) don't provide native async file I/O APIs. This means:

- `AsynchronousFileChannel` = Hidden thread pool + Blocking I/O + Callback wrapper
- **Virtual Threads** = Explicit thread pool + Blocking I/O + Simpler code

Since both approaches use threads internally, Virtual Threads eliminate the callback complexity while delivering **better performance** (1.33x faster for concurrent reads). We get:
- ✅ Simpler, more maintainable code
- ✅ Better performance
- ✅ Full control over thread pool sizing
- ✅ No hidden thread pool surprises

**Conclusion**: We chose **Virtual Threads + Blocking I/O** as it delivers superior concurrent performance without callback complexity. The "async" in `AsynchronousFileChannel` was just hidden threads anyway.

> **Note**: The async NIO implementation was removed in favor of the simpler and faster virtual thread approach. See commit [`40fba4b`](https://github.com/durable-streams/durable-streams-java/commit/40fba4b432112779bd3d8ef582ded54f836f20c8) for the original benchmark code.

## JSON mode

JSON mode is required by the protocol and implemented via the JSON SPI. You can use the Jackson module or provide your own codec implementation.

- Default codec discovery uses `ServiceLoader` via `StreamCodecProvider`.
- To avoid Jackson, ship your own module that implements `JsonCodec` and `StreamCodecProvider` for `application/json`.

## RocksDB native binaries

RocksDB JNI publishes OS-specific classifier jars. This project selects a classifier at runtime so builds stay small.

- Default: detect current OS
- Override with Gradle property: `-ProcksdbClassifier=win64`
- Override with env var: `ROCKSDB_CLASSIFIER=win64`

Common classifiers: `win64`, `linux64`, `osx`.

Example:

```
./gradlew :durable-streams-server-core:build -ProcksdbClassifier=linux64
```

## Client usage

Basic usage:

```java
import io.durablestreams.client.AppendRequest;
import io.durablestreams.client.CreateRequest;
import io.durablestreams.client.DurableStreamsClient;
import io.durablestreams.client.ReadRequest;
import io.durablestreams.core.Offset;

DurableStreamsClient client = DurableStreamsClient.create(); // uses JDK internal HTTP client by default

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
    done.await();
}
```

### Spring WebFlux

```java
package com.example.durable.streams.webmvc.webflux;

import io.durablestreams.server.core.CachePolicy;
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.CursorPolicy;
import io.durablestreams.spring.webflux.DurableStreamsWebFluxAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class RouterConfig {
  @Bean
  public DurableStreamsHandler durableStreamsHandler() {
    return DurableStreamsHandler.builder(new InMemoryStreamStore())
            .cursorPolicy(new CursorPolicy(Clock.systemUTC()))
            .cachePolicy(CachePolicy.defaultPrivate())
            .longPollTimeout(Duration.ofSeconds(25))
            .sseMaxDuration(Duration.ofSeconds(60))
            .build();
  }

  @Bean
  public DurableStreamsWebFluxAdapter durableStreamsWebFluxAdapter(DurableStreamsHandler handler) {
    return new DurableStreamsWebFluxAdapter(handler);
  }

  @Bean
  public RouterFunction<ServerResponse> durableStreamsRoutes(DurableStreamsWebFluxAdapter adapter) {
    return RouterFunctions.route()
                          .add(RouterFunctions.route(req -> true, adapter::handle))
                          .build();
  }
}
```

### Micronaut

```java
import io.durablestreams.micronaut.DurableStreamsMicronautAdapter;
import io.durablestreams.server.core.CachePolicy;
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.CursorPolicy;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.time.Clock;
import java.time.Duration;

@ExecuteOn(TaskExecutors.BLOCKING)
@Produces(MediaType.ALL)
@Consumes(MediaType.ALL)
@Controller("/")
final class DurableStreamsController {
  private final DurableStreamsHandler handler = DurableStreamsHandler.builder(new InMemoryStreamStore())
          .cursorPolicy(new CursorPolicy(Clock.systemUTC()))
          .cachePolicy(CachePolicy.defaultPrivate())
          .longPollTimeout(Duration.ofSeconds(25))
          .sseMaxDuration(Duration.ofSeconds(60))
          .build();

  @Get("/{+path}")
  HttpResponse<?> get(@PathVariable("path") String path, HttpRequest<byte[]> request) {
    return handle(request);
  }

  @Post("/{+path}")
  HttpResponse<?> post(@PathVariable("path") String path, HttpRequest<byte[]> request) {
    return handle(request);
  }

  @Put("/{+path}")
  HttpResponse<?> put(@PathVariable("path") String path, HttpRequest<byte[]> request) {
    return handle(request);
  }

  @Delete("/{+path}")
  HttpResponse<?> delete(@PathVariable("path") String path, HttpRequest<byte[]> request) {
    return handle(request);
  }

  private HttpResponse<?> handle(HttpRequest<byte[]> request) {
    return DurableStreamsMicronautAdapter.handle(request, handler);
  }
}
```

### Quarkus (RESTEasy Reactive)

```java
import io.durablestreams.quarkus.DurableStreamsQuarkusAdapter;
import io.durablestreams.server.core.CachePolicy;
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.CursorPolicy;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.time.Clock;
import java.time.Duration;

@Path("/")
public class DurableStreamsResource {
    private final DurableStreamsHandler handler = DurableStreamsHandler.builder(new InMemoryStreamStore())
            .cursorPolicy(new CursorPolicy(Clock.systemUTC()))
            .cachePolicy(CachePolicy.defaultPrivate())
            .longPollTimeout(Duration.ofSeconds(25))
            .sseMaxDuration(Duration.ofSeconds(60))
            .build();
  
    @GET
    @Path("{path:.*}")
    public Response get(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
      return DurableStreamsQuarkusAdapter.handle(HttpMethod.GET, uriInfo, headers, null, handler);
    }
  
    @POST
    @Path("{path:.*}")
    public Response post(@Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
      return DurableStreamsQuarkusAdapter.handle(HttpMethod.POST, uriInfo, headers, body, handler);
    }
  
    @PUT
    @Path("{path:.*}")
    public Response put(@Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
      return DurableStreamsQuarkusAdapter.handle(HttpMethod.PUT, uriInfo, headers, body, handler);
    }
  
    @DELETE
    @Path("{path:.*}")
    public Response delete(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
      return DurableStreamsQuarkusAdapter.handle(HttpMethod.DELETE, uriInfo, headers, null, handler);
    }
  
    @HEAD
    @Path("{path:.*}")
    public Response head(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
      return DurableStreamsQuarkusAdapter.handle(HttpMethod.HEAD, uriInfo, headers, null, handler);
    }
}

```

### Ktor (Netty)

```kotlin
import io.durablestreams.ktor.DurableStreamsKtorAdapter
import io.durablestreams.server.core.CachePolicy
import io.durablestreams.server.core.DurableStreamsHandler
import io.durablestreams.server.core.InMemoryStreamStore
import io.durablestreams.server.spi.CursorPolicy
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.handle
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Duration

fun main() {
    embeddedServer(Netty, port = 4435, module = Application::module).start(wait = true)
}

fun Application.module() {
    val handler = DurableStreamsHandler.builder(InMemoryStreamStore())
        .cursorPolicy(CursorPolicy(Clock.systemUTC()))
        .cachePolicy(CachePolicy.defaultPrivate())
        .longPollTimeout(Duration.ofSeconds(25))
        .sseMaxDuration(Duration.ofSeconds(60))
        .build()

    routing {
        route("{path...}") {
            handle {
                DurableStreamsKtorAdapter.handle(call, handler)
            }
        }
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
