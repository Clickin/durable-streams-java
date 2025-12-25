# Modules

Durable Streams Java 구현체의 모듈 구조와 각 모듈의 역할을 설명합니다.

## 모듈 개요

```
durable-streams-java/
│
├── durable-streams-core/               # 프로토콜 기반 (의존성 0)
├── durable-streams-client-jdk/         # JDK HttpClient 클라이언트
├── durable-streams-server-spi/         # 서버 스토리지 추상화
├── durable-streams-server-core/        # 프로토콜 엔진
│
├── durable-streams-json-jackson/       # JSON mode (Jackson)
│
├── durable-streams-reactive-adapters/  # Flow ↔ RS Publisher
├── durable-streams-client-reactor/     # Reactor 어댑터
├── durable-streams-client-rxjava3/     # RxJava3 어댑터
├── durable-streams-kotlin/             # Kotlin coroutines
│
├── durable-streams-spring-webflux/     # Spring WebFlux 서버
├── durable-streams-spring-webmvc/      # Spring MVC 서버
├── durable-streams-spring-boot-starter/
│
├── durable-streams-micronaut-server/   # Micronaut 서버
├── durable-streams-quarkus-server/     # Quarkus 서버
│
└── durable-streams-testkit/            # 테스트 도구
```

---

## Core 모듈

### durable-streams-core

**역할**: 프로토콜의 기본 타입과 유틸리티

**의존성**: 없음 (JDK 21+ only)

```kotlin
// build.gradle.kts
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // 의존성 없음
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
```

**패키지 구조**:

```
io.durablestreams.core
├── Protocol              # 헤더/쿼리 상수
├── Offset                # Offset 값 객체
├── StreamEvent           # sealed interface
│   ├── Data              # 데이터 이벤트
│   ├── Control           # SSE control 이벤트
│   └── UpToDate          # tail 도달 이벤트
├── StreamConfig          # 스트림 생성 설정
├── ReadMode              # CATCH_UP, LONG_POLL, SSE
├── DurableStreamsException
└── internal
    ├── SseParser         # SSE 파싱
    └── UrlBuilder        # 쿼리 파라미터 정렬
```

**핵심 API**:

```java
// Protocol constants
public final class Protocol {
    public static final String H_STREAM_NEXT_OFFSET = "Stream-Next-Offset";
    public static final String H_STREAM_UP_TO_DATE = "Stream-Up-To-Date";
    public static final String H_STREAM_CURSOR = "Stream-Cursor";
    public static final String H_STREAM_TTL = "Stream-TTL";
    public static final String H_STREAM_EXPIRES_AT = "Stream-Expires-At";
    public static final String H_STREAM_SEQ = "Stream-Seq";
    
    public static final String Q_OFFSET = "offset";
    public static final String Q_LIVE = "live";
    public static final String Q_CURSOR = "cursor";
    
    public static final String LIVE_LONG_POLL = "long-poll";
    public static final String LIVE_SSE = "sse";
    
    public static final String OFFSET_BEGINNING = "-1";
}

// Offset validation
public record Offset(String value) {
    public Offset {
        Objects.requireNonNull(value);
        if (containsForbiddenChars(value)) {
            throw new IllegalArgumentException("Offset contains forbidden chars");
        }
    }
    
    public static Offset beginning() {
        return new Offset(Protocol.OFFSET_BEGINNING);
    }
}

// Stream events
public sealed interface StreamEvent 
    permits StreamEvent.Data, StreamEvent.Control, StreamEvent.UpToDate {
    
    record Data(ByteBuffer bytes, String contentType) implements StreamEvent {}
    record Control(Offset nextOffset, Optional<String> cursor) implements StreamEvent {}
    record UpToDate(Offset nextOffset) implements StreamEvent {}
}
```

---

### durable-streams-client-jdk

**역할**: JDK HttpClient 기반 클라이언트 구현

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-core"))
    // JDK HttpClient (java.net.http) - JDK 내장
}
```

**핵심 API**:

```java
public interface DurableStreamsClient {
    // 동기 API
    CreateResult create(CreateRequest req) throws IOException, InterruptedException;
    AppendResult append(AppendRequest req) throws IOException, InterruptedException;
    HeadResult head(URI streamUrl) throws IOException, InterruptedException;
    void delete(URI streamUrl) throws IOException, InterruptedException;
    ReadResult readCatchUp(ReadRequest req) throws IOException, InterruptedException;
    
    // 비동기 스트리밍 API
    Flow.Publisher<StreamEvent> subscribeLongPoll(LiveLongPollRequest req);
    Flow.Publisher<StreamEvent> subscribeSse(LiveSseRequest req);
}

// Factory method
public static DurableStreamsClient create() {
    return new JdkDurableStreamsClient(HttpClient.newHttpClient());
}

public static DurableStreamsClient create(HttpClient httpClient) {
    return new JdkDurableStreamsClient(httpClient);
}
```

---

### durable-streams-server-spi

**역할**: 서버 구현을 위한 추상화 계층

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-core"))
}
```

**핵심 인터페이스**:

```java
public interface StreamStore {
    // 스트림 생성
    StreamInfo createIfAbsent(URI url, StreamConfig config, @Nullable byte[] initialBody);
    
    // 데이터 추가
    AppendOutcome append(URI url, String contentType, @Nullable String seq, byte[] body);
    
    // 읽기 (catch-up)
    ReadChunk read(URI url, String offset, int maxBytes);
    
    // 읽기 (live - 데이터 대기)
    Optional<ReadChunk> waitForData(URI url, String offset, Duration timeout);
    
    // 메타데이터
    StreamInfo head(URI url);
    
    // 삭제
    void delete(URI url);
}

public interface OffsetGenerator {
    String generate();
    boolean isValid(String offset);
}

public interface CursorPolicy {
    String nextCursor(@Nullable String clientCursor);
}

// Value objects
public record StreamInfo(
    URI url,
    String contentType,
    Offset tailOffset,
    @Nullable Duration ttl,
    @Nullable Instant expiresAt
) {}

public record ReadChunk(
    byte[] data,
    Offset nextOffset,
    boolean upToDate,
    @Nullable String cursor,
    @Nullable String etag
) {}

public record AppendOutcome(
    Offset nextOffset
) {}
```

---

### durable-streams-server-core

**역할**: 프레임워크 독립 프로토콜 엔진

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-server-spi"))
}
```

**핵심 API**:

```java
public class ProtocolEngine {
    private final StreamStore store;
    private final CursorPolicy cursorPolicy;
    
    public ProtocolEngine(StreamStore store, CursorPolicy cursorPolicy) {
        this.store = store;
        this.cursorPolicy = cursorPolicy;
    }
    
    public HttpResponse handleCreate(HttpRequest request);
    public HttpResponse handleAppend(HttpRequest request);
    public HttpResponse handleRead(HttpRequest request);
    public HttpResponse handleHead(HttpRequest request);
    public HttpResponse handleDelete(HttpRequest request);
    
    // SSE는 스트리밍 응답 필요
    public Flow.Publisher<SseEvent> handleSse(HttpRequest request);
}

// 프레임워크 독립 HTTP 추상화
public interface HttpRequest {
    String method();
    URI uri();
    Optional<String> header(String name);
    byte[] body();
}

public interface HttpResponse {
    int status();
    Map<String, String> headers();
    byte[] body();
}
```

---

## JSON 모듈

### durable-streams-json-jackson

**역할**: JSON mode 지원 (array flatten, validation)

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
}
```

**핵심 API**:

```java
public class JsonStreamHelper {
    private final ObjectMapper mapper;
    
    // Append: 단일 값을 array로 감싸기
    public byte[] wrapForAppend(Object value);
    
    // Append: 배치 값 직렬화
    public byte[] serializeBatch(List<?> values);
    
    // Read: JSON array 파싱
    public <T> List<T> parseMessages(byte[] data, Class<T> type);
    
    // SSE control event 파싱
    public ControlEvent parseControl(String json);
}

public record ControlEvent(
    String streamNextOffset,
    @Nullable String streamCursor
) {}
```

---

## Reactive Adapters

### durable-streams-reactive-adapters

**역할**: JDK Flow ↔ Reactive Streams 변환

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-core"))
    api("org.reactivestreams:reactive-streams:1.0.4")
}
```

**핵심 API**:

```java
public final class FlowAdapters {
    public static <T> Flow.Publisher<T> toFlow(Publisher<T> publisher);
    public static <T> Publisher<T> toReactiveStreams(Flow.Publisher<T> publisher);
}
```

---

### durable-streams-client-reactor

**역할**: Reactor Flux 어댑터

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-client-jdk"))
    api(project(":durable-streams-reactive-adapters"))
    api("io.projectreactor:reactor-core:3.6.5")
}
```

**핵심 API**:

```java
public class ReactorDurableStreamsClient {
    private final DurableStreamsClient delegate;
    
    // Mono 기반 동기 API
    public Mono<CreateResult> create(CreateRequest req);
    public Mono<AppendResult> append(AppendRequest req);
    public Mono<HeadResult> head(URI streamUrl);
    public Mono<Void> delete(URI streamUrl);
    public Mono<ReadResult> readCatchUp(ReadRequest req);
    
    // Flux 기반 스트리밍 API
    public Flux<StreamEvent> subscribeLongPoll(LiveLongPollRequest req);
    public Flux<StreamEvent> subscribeSse(LiveSseRequest req);
}
```

---

### durable-streams-client-rxjava3

**역할**: RxJava3 Flowable 어댑터

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-client-jdk"))
    api(project(":durable-streams-reactive-adapters"))
    api("io.reactivex.rxjava3:rxjava:3.1.8")
}
```

---

### durable-streams-kotlin

**역할**: Kotlin coroutines 지원

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-client-jdk"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-jdk9:1.8.0")
}
```

**핵심 API**:

```kotlin
class KotlinDurableStreamsClient(
    private val delegate: DurableStreamsClient
) {
    // suspend 기반 동기 API
    suspend fun create(req: CreateRequest): CreateResult
    suspend fun append(req: AppendRequest): AppendResult
    suspend fun head(streamUrl: URI): HeadResult
    suspend fun delete(streamUrl: URI)
    suspend fun readCatchUp(req: ReadRequest): ReadResult
    
    // Flow 기반 스트리밍 API
    fun subscribeLongPoll(req: LiveLongPollRequest): Flow<StreamEvent>
    fun subscribeSse(req: LiveSseRequest): Flow<StreamEvent>
}
```

---

## Spring 통합

### durable-streams-spring-webflux

**역할**: Spring WebFlux 서버 통합

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-server-core"))
    api("org.springframework:spring-webflux:6.1.6")
}
```

**핵심 구성**:

```java
@Configuration
public class DurableStreamsWebFluxConfiguration {
    
    @Bean
    public RouterFunction<ServerResponse> durableStreamsRoutes(
            ProtocolEngine engine) {
        return RouterFunctions.route()
            .PUT("/**", request -> handleCreate(engine, request))
            .POST("/**", request -> handleAppend(engine, request))
            .GET("/**", request -> handleRead(engine, request))
            .HEAD("/**", request -> handleHead(engine, request))
            .DELETE("/**", request -> handleDelete(engine, request))
            .build();
    }
    
    // SSE 응답은 Flux<ServerSentEvent>로 스트리밍
    private Mono<ServerResponse> handleSseRead(
            ProtocolEngine engine, ServerRequest request) {
        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(/* Flux<ServerSentEvent> */);
    }
}
```

---

### durable-streams-spring-boot-starter

**역할**: Spring Boot 자동 설정

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-spring-webflux"))
    implementation("org.springframework.boot:spring-boot-autoconfigure:3.2.5")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.2.5")
}
```

**Properties**:

```yaml
durable-streams:
  enabled: true
  base-path: /streams
  storage:
    type: in-memory  # in-memory | postgres | s3
  cursor:
    interval-seconds: 20
    jitter-max-seconds: 3600
```

---

## Micronaut/Quarkus 통합

### durable-streams-micronaut-server

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-server-core"))
    api("io.micronaut:micronaut-http-server-netty:4.4.2")
}
```

**SSE 응답**:

Micronaut에서 SSE는 `Publisher<Event<T>>` 반환:

```java
@Controller("/streams")
public class DurableStreamsController {
    
    @Get("/{+path}")
    @Produces(MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> readSse(
            @PathVariable String path,
            @QueryValue String offset) {
        // ... Publisher<Event<String>> 반환
    }
}
```

---

### durable-streams-quarkus-server

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-server-core"))
    api("io.quarkus:quarkus-rest:3.10.0")
}
```

**SSE 응답**:

Quarkus에서 SSE는 `Multi<OutboundSseEvent>` 반환:

```java
@Path("/streams")
public class DurableStreamsResource {
    
    @GET
    @Path("/{path:.*}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> readSse(
            @PathParam("path") String path,
            @QueryParam("offset") String offset) {
        // ... Multi<OutboundSseEvent> 반환
    }
}
```

---

## 테스트 모듈

### durable-streams-testkit

**역할**: 테스트 도구 및 conformance test 연동

**의존성**:

```kotlin
dependencies {
    api(project(":durable-streams-server-core"))
    implementation("org.junit.jupiter:junit-jupiter:5.10.2")
    implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

**기능**:

1. **In-memory StreamStore**: 테스트용 메모리 스토리지
2. **MockServer**: 클라이언트 테스트용 모의 서버
3. **Conformance Test Runner**: upstream 테스트 실행

```java
public class InMemoryStreamStore implements StreamStore {
    // 테스트용 in-memory 구현
}

public class ConformanceTestRunner {
    public void runServerTests(String baseUrl);
    public void runClientTests(DurableStreamsClient client, String serverUrl);
}
```

---

## 의존성 그래프

```
                        ┌─────────────────────────────────┐
                        │   durable-streams-spring-boot   │
                        │         -starter                │
                        └─────────────────────────────────┘
                                       │
                        ┌──────────────┴──────────────┐
                        │                             │
              ┌─────────▼──────────┐    ┌─────────────▼─────────────┐
              │  spring-webflux    │    │    spring-webmvc          │
              └────────────────────┘    └───────────────────────────┘
                        │                             │
                        └──────────────┬──────────────┘
                                       │
                        ┌──────────────▼──────────────┐
                        │    durable-streams          │
                        │    -server-core             │
                        └─────────────────────────────┘
                                       │
                        ┌──────────────▼──────────────┐
                        │    durable-streams          │
                        │    -server-spi              │
                        └─────────────────────────────┘
                                       │
┌────────────────────┐  ┌──────────────▼──────────────┐
│  client-reactor    │  │                             │
│  client-rxjava3    │──│    durable-streams          │
│  kotlin            │  │    -core                    │
└────────────────────┘  │                             │
         │              └─────────────────────────────┘
         │                             ▲
         │              ┌──────────────┴──────────────┐
         │              │    durable-streams          │
         └──────────────│    -client-jdk              │
                        └─────────────────────────────┘
```

---

## Gradle 설정 예시

### settings.gradle.kts

```kotlin
rootProject.name = "durable-streams-java"

include(
    "durable-streams-core",
    "durable-streams-client-jdk",
    "durable-streams-server-spi",
    "durable-streams-server-core",
    "durable-streams-json-jackson",
    "durable-streams-reactive-adapters",
    "durable-streams-client-reactor",
    "durable-streams-client-rxjava3",
    "durable-streams-kotlin",
    "durable-streams-spring-webflux",
    "durable-streams-spring-webmvc",
    "durable-streams-spring-boot-starter",
    "durable-streams-micronaut-server",
    "durable-streams-quarkus-server",
    "durable-streams-testkit"
)
```

### Root build.gradle.kts

```kotlin
plugins {
    java
    `maven-publish`
}

allprojects {
    group = "io.durablestreams"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    
    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }
    
    repositories {
        mavenCentral()
    }
    
    tasks.withType<Test> {
        useJUnitPlatform()
    }
    
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
    }
}
```
