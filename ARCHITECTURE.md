# Architecture

Durable Streams Java 구현체의 전체 아키텍처를 설명합니다.

## 설계 철학

### 1. Core는 프로토콜 자체에만 집중

Core 모듈은 Durable Streams 프로토콜의 순수한 구현입니다:
- HTTP 메서드/헤더/쿼리 파라미터 정의
- Offset/Cursor 모델링
- SSE 파싱
- 상태 머신 (catch-up → live 전환)

### 2. 프레임워크 독립성

```
┌─────────────────────────────────────────────────────────────────┐
│                     Application Layer                           │
├─────────────────────────────────────────────────────────────────┤
│  Spring Boot  │  Micronaut   │   Quarkus   │  Plain Java/Kotlin │
│   Starter     │   Module     │   Module    │                    │
├───────────────┼──────────────┼─────────────┼────────────────────┤
│               │              │             │                    │
│  WebFlux      │  Micronaut   │  Quarkus    │                    │
│  Glue         │  HTTP        │  REST       │                    │
├───────────────┴──────────────┴─────────────┴────────────────────┤
│                     Reactive Adapters                           │
│           (Reactor / RxJava3 / Kotlin Flow)                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│                   durable-streams-server-core                   │
│                   (Protocol Engine)                             │
│                                                                 │
├────────────────────────┬────────────────────────────────────────┤
│                        │                                        │
│   durable-streams      │      durable-streams-server-spi        │
│   -client-jdk          │      (Storage/Policy Abstractions)     │
│                        │                                        │
├────────────────────────┴────────────────────────────────────────┤
│                                                                 │
│                     durable-streams-core                        │
│          (Protocol Constants, Models, Utilities)               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3. 비동기 스트림 타입 전략

**Core**: `java.util.concurrent.Flow.Publisher`
- JDK 9+ 표준
- 외부 의존성 없음
- Reactive Streams 호환

**Adapters**:
- `durable-streams-client-reactor`: `Flow.Publisher` ↔ `Flux`
- `durable-streams-client-rxjava3`: `Flow.Publisher` ↔ `Flowable`
- `durable-streams-kotlin`: `Flow.Publisher` ↔ `kotlinx.coroutines.flow.Flow`

## 모듈 계층 구조

### Layer 0: Core Foundation

```
durable-streams-core
├── Protocol.java          # 헤더/쿼리 상수
├── Offset.java            # Offset 값 객체 (validation 포함)
├── StreamEvent.java       # sealed interface (Data, Control, UpToDate)
├── StreamConfig.java      # 스트림 설정 (contentType, TTL 등)
├── DurableStreamsException.java
└── internal/
    ├── SseParser.java     # SSE 이벤트 파싱
    └── UrlBuilder.java    # 쿼리 파라미터 정렬
```

### Layer 1: Client & Server SPI

```
durable-streams-client
├── DurableStreamsClient.java       # 메인 인터페이스
├── JdkDurableStreamsClient.java    # JDK HttpClient 구현
├── LiveSubscription.java           # 실시간 구독 핸들
└── internal/
    ├── CatchUpLoop.java            # Catch-up 상태 머신
    ├── LongPollLoop.java           # Long-poll 재연결 루프
    └── SseLoop.java                # SSE 재연결 루프

durable-streams-server-spi
├── StreamStore.java                # 스토리지 추상화
├── OffsetGenerator.java            # Offset 생성 전략
├── CursorPolicy.java               # Cursor 생성 (interval+jitter)
├── ReadChunk.java                  # 읽기 결과
└── StreamInfo.java                 # 스트림 메타데이터
```

### Layer 2: Server Core (Protocol Engine)

```
durable-streams-server-core
├── ProtocolEngine.java             # HTTP 요청 처리 엔진
├── handlers/
│   ├── CreateHandler.java          # PUT 처리
│   ├── AppendHandler.java          # POST 처리
│   ├── ReadHandler.java            # GET catch-up
│   ├── LiveLongPollHandler.java    # GET long-poll
│   ├── LiveSseHandler.java         # GET SSE
│   ├── HeadHandler.java            # HEAD 처리
│   └── DeleteHandler.java          # DELETE 처리
└── HttpRequest.java / HttpResponse.java  # 프레임워크 독립 추상화
```

### Layer 3: Framework Glue

```
durable-streams-spring-webflux
├── DurableStreamsRouter.java       # RouterFunction 설정
├── SseResponseWriter.java          # SSE 응답 스트리밍
└── WebFluxHttpAdapter.java         # ServerRequest/Response 변환

durable-streams-spring-boot-starter
├── DurableStreamsAutoConfiguration.java
├── DurableStreamsProperties.java
└── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

durable-streams-micronaut
├── DurableStreamsController.java   # @Controller 기반
└── MicronautHttpAdapter.java

durable-streams-quarkus
├── DurableStreamsResource.java     # JAX-RS 기반
└── QuarkusHttpAdapter.java
```

## 데이터 흐름

### Client Read Flow

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   User      │────▶│  DurableStreams  │────▶│  JDK HttpClient │
│   Code      │     │  Client          │     │                 │
└─────────────┘     └──────────────────┘     └─────────────────┘
                            │                        │
                            │                        ▼
                            │                 ┌─────────────────┐
                            │                 │    Server       │
                            │                 └─────────────────┘
                            │                        │
                            ▼                        │
                    ┌──────────────────┐             │
                    │  State Machine   │◀────────────┘
                    │  (Catch-up/Live) │
                    └──────────────────┘
                            │
                            ▼
                    ┌──────────────────┐
                    │  StreamObserver  │
                    │  (User Callback) │
                    └──────────────────┘
```

### Server Request Flow

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  HTTP Request   │────▶│  Framework Glue  │────▶│  Protocol       │
│  (WebFlux/etc)  │     │  (Adapter)       │     │  Engine         │
└─────────────────┘     └──────────────────┘     └─────────────────┘
                                                        │
                                                        ▼
                                                ┌─────────────────┐
                                                │  Handler        │
                                                │  (PUT/POST/GET) │
                                                └─────────────────┘
                                                        │
                                                        ▼
                                                ┌─────────────────┐
                                                │  StreamStore    │
                                                │  (SPI)          │
                                                └─────────────────┘
                                                        │
                                                        ▼
                                                ┌─────────────────┐
                                                │  Storage        │
                                                │  Implementation │
                                                └─────────────────┘
```

## 핵심 상태 머신

### Client Read State Machine

```
                    ┌─────────────────────┐
                    │       START         │
                    └─────────────────────┘
                              │
                              ▼
                    ┌─────────────────────┐
           ┌───────│     CATCH_UP        │◀──────┐
           │       └─────────────────────┘       │
           │                  │                  │
           │     Stream-Up-To-Date: false       │
           │                  │                  │
           │                  ▼                  │
           │       ┌─────────────────────┐       │
           │       │   FETCHING_MORE     │───────┘
           │       └─────────────────────┘
           │
    Stream-Up-To-Date: true
           │
           ▼
┌─────────────────────┐
│       LIVE          │
└─────────────────────┘
         │
         ├─── mode=long-poll ───▶ ┌──────────────────┐
         │                        │  LONG_POLL_WAIT  │
         │                        └──────────────────┘
         │                               │
         │                     ┌─────────┴─────────┐
         │                     │                   │
         │                  200 OK              204 No Content
         │                     │                   │
         │                     ▼                   ▼
         │              ┌────────────┐      ┌────────────┐
         │              │ Emit Data  │      │ Emit UTD   │
         │              └────────────┘      └────────────┘
         │                     │                   │
         │                     └─────────┬─────────┘
         │                               │
         │                     Update offset/cursor
         │                               │
         │                     ┌─────────▼─────────┐
         │                     │   Reconnect       │
         │                     └───────────────────┘
         │
         └─── mode=sse ──────▶ ┌──────────────────┐
                               │   SSE_STREAM     │
                               └──────────────────┘
                                       │
                           ┌───────────┴───────────┐
                           │                       │
                    event: data              event: control
                           │                       │
                           ▼                       ▼
                    ┌────────────┐          ┌────────────┐
                    │ Emit Data  │          │ Update     │
                    │            │          │ offset/    │
                    └────────────┘          │ cursor     │
                                            └────────────┘
                                                   │
                               Connection closed (~60s)
                                                   │
                                         ┌─────────▼─────────┐
                                         │   Reconnect with  │
                                         │   last offset     │
                                         └───────────────────┘
```

## 캐싱 전략

### CDN/Proxy Collapsing

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Client A   │──┐  │  Client B   │──┐  │  Client C   │
└─────────────┘  │  └─────────────┘  │  └─────────────┘
                 │                   │         │
                 │   Same offset     │         │
                 │   + cursor        │         │
                 ▼                   ▼         ▼
              ┌────────────────────────────────────┐
              │           CDN Edge                 │
              │  (Collapsed to single request)     │
              └────────────────────────────────────┘
                              │
                              │ Single upstream request
                              ▼
                    ┌───────────────────┐
                    │      Origin       │
                    │      Server       │
                    └───────────────────┘
```

### Query Parameter Ordering

프로토콜 스펙에서 권장하는 쿼리 파라미터 정렬:

```java
// 캐시 hit율 향상을 위해 key를 lexicographic 정렬
// Before: ?live=long-poll&offset=abc&cursor=xyz
// After:  ?cursor=xyz&live=long-poll&offset=abc

public static URI withSortedQuery(URI base, Map<String, String> params) {
    var sorted = new TreeMap<>(params);  // Natural ordering
    // ... build query string
}
```

## 확장 포인트

### 1. Storage Backends

```java
public interface StreamStore {
    StreamInfo createIfAbsent(URI url, StreamConfig config, byte[] initialBody);
    AppendOutcome append(URI url, String contentType, String seq, byte[] body);
    ReadChunk read(URI url, String offset, int maxBytes);
    Optional<ReadChunk> waitForData(URI url, String offset, Duration timeout);
    StreamInfo head(URI url);
    void delete(URI url);
}
```

구현 예시:
- In-memory (개발용)
- PostgreSQL (JSONB + LISTEN/NOTIFY)
- S3 + DynamoDB
- Redis Streams
- Kafka (읽기 전용)

### 2. Offset Generation

```java
public interface OffsetGenerator {
    String generate();  // Must be lexicographically increasing
    boolean isValid(String offset);
}
```

구현 예시:
- ULID 기반
- KSUID 기반
- Timestamp + sequence

### 3. Authentication/Authorization

```java
public interface StreamAuthorizer {
    boolean canCreate(URI streamUrl, Principal principal);
    boolean canAppend(URI streamUrl, Principal principal);
    boolean canRead(URI streamUrl, Principal principal);
    boolean canDelete(URI streamUrl, Principal principal);
}
```

## 성능 고려사항

### 1. 메모리 관리

- Streaming body 처리 (큰 chunk 대응)
- ByteBuffer pooling
- Backpressure 지원 (Flow.Publisher)

### 2. 연결 관리

- HttpClient 연결 풀링
- SSE 연결 ~60초 주기 갱신
- Long-poll 타임아웃 관리

### 3. 동시성

- Virtual Threads 친화적 설계
- Lock-free offset 생성
- Non-blocking 스토리지 접근
