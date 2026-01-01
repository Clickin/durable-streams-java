# durable-streams-java

Durable Streams 프로토콜의 Java 17+ 구현체.

> **참고:** 이 라이브러리는 Java 17에서 실행되지만, **고성능 동시성 처리와 확장성을 위해 가상 스레드(Virtual Threads)를 지원하는 JDK 21+ 사용을 강력히 권장합니다.**

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com/durable-streams/durable-streams-java)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

[English README](README.md)

## 빌드 모듈

- `durable-streams-core` - 프로토콜 타입 및 헬퍼
- `durable-streams-client` - JDK HttpClient 기반 클라이언트
- `durable-streams-json-spi` - JSON 직렬화 SPI (라이브러리 비종속적)
- `durable-streams-json-jackson` - Jackson 기반 JSON 모드 구현 (선택 사항)
- `durable-streams-server-spi` - 서버 저장소/정책 추상화
- `durable-streams-server-core` - 프로토콜 엔진
- `durable-streams-servlet` - Servlet 통합 헬퍼(Spring MVC 등)
- `durable-streams-spring-webflux` - Spring WebFlux 통합 헬퍼
- `durable-streams-micronaut` - Micronaut 통합 헬퍼
- `durable-streams-quarkus` - Quarkus 통합 헬퍼
- `durable-streams-conformance-runner` - 적합성 테스트용 서버/클라이언트 러너

## 주요 기능 및 아키텍처

- **고성능 스토리지**: Java 21+의 가상 스레드(Virtual Threads) 위에서 동기식 `FileChannel` I/O를 사용하며, 제한된 크기의 전용 I/O 실행기를 통해 플랫폼 스레드 고갈을 방지합니다.
- **엄격한 동시성 제어**: 스트림별 `ReentrantLock`을 통해 원자적인 append 및 메타데이터 업데이트를 보장합니다..
- **효율적인 대기 처리**: `ConcurrentLinkedQueue`를 사용한 락-프리(lock-free) 대기 큐로 수천 개의 동시 롱폴링/SSE 연결을 효율적으로 처리합니다.
- **프로토콜 완벽 준수**: Durable Streams 프로토콜 적합성 테스트 131/131 통과.
    - 엄격한 바이트 오프셋 추적
    - 저메모리 스트리밍 JSON 파싱 (Jackson 기본값)
    - 정확한 ETag 생성 및 캐시 제어
    - 쓰기 조정을 위한 `Stream-Seq`의 올바른 처리

## JSON 모드

프로토콜에서 요구하는 JSON 모드는 JSON SPI를 통해 구현됩니다. Jackson 모듈을 사용하거나 직접 코덱을 구현할 수 있습니다.

- 기본 코덱 발견은 `StreamCodecProvider`를 통한 `ServiceLoader`를 사용합니다.
- Jackson을 피하고 싶다면 `JsonCodec`과 `StreamCodecProvider`를 구현한 자체 모듈을 `application/json`용으로 제공하면 됩니다.

## 클라이언트 사용법

기본 사용법:

```java
import io.durablestreams.client.AppendRequest;
import io.durablestreams.client.CreateRequest;
import io.durablestreams.client.DurableStreamsClient;
import io.durablestreams.client.ReadRequest;
import io.durablestreams.core.Offset;

DurableStreamsClient client = DurableStreamsClient.create(); // 기본값으로 JDK 내장 HTTP client 사용

client.create(new CreateRequest(streamUrl, "application/json", null, null));
client.append(new AppendRequest(streamUrl, "application/json", null, dataStream));
var read = client.readCatchUp(new ReadRequest(streamUrl, Offset.beginning(), null));
```

커스텀 전송 계층 사용 (ServiceLoader 미사용, GraalVM 친화적):

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

## 서버 통합 예제

다음은 공통 프레임워크에 프로토콜 핸들러를 연결하는 예제입니다. `durable-streams-conformance-runner`에서 사용된 것과 동일한 핸들러 흐름을 따릅니다.

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

## 반응형 통합 예제 (Reactive)

### Reactor

Gradle 의존성:

```kotlin
dependencies {
    implementation("org.reactivestreams:reactive-streams-flow-adapters:1.0.2")
    implementation("io.projectreactor:reactor-core:3.7.1")
}
```

사용법:

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

Gradle 의존성:

```kotlin
dependencies {
    implementation("org.reactivestreams:reactive-streams-flow-adapters:1.0.2")
    implementation("io.reactivex.rxjava3:rxjava:3.1.9")
}
```

사용법:

```java
import io.durablestreams.client.DurableStreamsClient;
import io.durablestreams.core.StreamEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.reactivestreams.FlowAdapters;

DurableStreamsClient client = DurableStreamsClient.create();
Flow.Publisher<StreamEvent> pub = client.subscribeLongPoll(request);
Flowable<StreamEvent> flowable = Flowable.fromPublisher(FlowAdapters.toPublisher(pub));
```

### Kotlin Coroutines

Gradle 의존성:

```kotlin
dependencies {
    implementation("org.reactivestreams:reactive-streams-flow-adapters:1.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.9.0")
}
```

사용법:

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
