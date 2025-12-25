# Testing Strategy

Durable Streams Java 구현체의 테스트 전략입니다.

## 테스트 계층

```
┌────────────────────────────────────────────────────────┐
│                  Conformance Tests                      │
│           (upstream 언어 중립 테스트 스위트)            │
├────────────────────────────────────────────────────────┤
│                  Integration Tests                      │
│         (클라이언트 ↔ 서버 통합 테스트)                │
├────────────────────────────────────────────────────────┤
│                    Unit Tests                          │
│          (모듈별 단위 테스트)                          │
└────────────────────────────────────────────────────────┘
```

---

## Upstream Conformance Tests

### 개요

Durable Streams 프로젝트는 언어 중립 conformance test suite를 제공합니다:
- **Server Tests**: 124개 테스트 케이스
- **Client Tests**: 110개 테스트 케이스

**Repository**: https://github.com/durable-streams/durable-streams/tree/main/packages/conformance-tests

### 테스트 실행 방법

#### 1. Upstream 저장소 클론

```bash
git clone https://github.com/durable-streams/durable-streams.git
cd durable-streams
pnpm install
```

#### 2. Java 서버 시작

```bash
# Java 서버 빌드 및 실행
cd /path/to/durable-streams-java
./gradlew :durable-streams-spring-boot-starter:bootRun
```

#### 3. Conformance Tests 실행

```bash
# upstream 저장소에서
cd packages/conformance-tests

# 서버 테스트 실행
STREAM_URL=http://localhost:8787 pnpm test:server

# 클라이언트 테스트 실행 (테스트 서버가 필요)
pnpm test:client
```

### CI 통합

#### GitHub Actions Workflow

```yaml
# .github/workflows/conformance.yml
name: Conformance Tests

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  conformance:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Build Java Server
        run: ./gradlew build -x test
      
      - name: Start Java Server
        run: |
          ./gradlew :durable-streams-spring-boot-starter:bootRun &
          sleep 10  # 서버 시작 대기
      
      - name: Clone upstream
        run: |
          git clone https://github.com/durable-streams/durable-streams.git upstream
          cd upstream && pnpm install
      
      - name: Run Server Conformance Tests
        run: |
          cd upstream/packages/conformance-tests
          STREAM_URL=http://localhost:8787 pnpm test:server
      
      - name: Stop Server
        run: pkill -f bootRun || true
```

### 테스트 카테고리

#### Server Conformance Tests

| 카테고리 | 테스트 수 | 설명 |
|----------|----------|------|
| Create | ~15 | PUT idempotency, TTL/Expires-At |
| Append | ~20 | POST validation, Stream-Seq |
| Read Catch-up | ~25 | GET, offset, chunking |
| Read Long-poll | ~20 | 204, timeout, cursor |
| Read SSE | ~25 | event format, control |
| Head | ~10 | metadata |
| Delete | ~10 | cleanup |

#### Client Conformance Tests

| 카테고리 | 테스트 수 | 설명 |
|----------|----------|------|
| Create/Append | ~15 | 쓰기 API |
| Catch-up | ~20 | 반복 읽기, Up-To-Date |
| Long-poll | ~25 | 재연결, cursor |
| SSE | ~30 | reconnect, control parsing |
| Error Handling | ~20 | 4xx/5xx 처리 |

---

## Unit Tests

### Core 모듈 테스트

```java
// OffsetTest.java
class OffsetTest {
    
    @Test
    void shouldAcceptValidOffset() {
        var offset = new Offset("abc123");
        assertThat(offset.value()).isEqualTo("abc123");
    }
    
    @Test
    void shouldRejectForbiddenCharacters() {
        assertThatThrownBy(() -> new Offset("abc,123"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("forbidden characters");
        
        assertThatThrownBy(() -> new Offset("abc&123"))
            .isInstanceOf(IllegalArgumentException.class);
        
        assertThatThrownBy(() -> new Offset("abc=123"))
            .isInstanceOf(IllegalArgumentException.class);
        
        assertThatThrownBy(() -> new Offset("abc?123"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    
    @Test
    void shouldAcceptSentinel() {
        var offset = Offset.beginning();
        assertThat(offset.value()).isEqualTo("-1");
    }
    
    @Test
    void shouldBeLexicographicallySortable() {
        var offset1 = new Offset("aaa");
        var offset2 = new Offset("bbb");
        var offset3 = new Offset("aab");
        
        assertThat(offset1.compareTo(offset2)).isLessThan(0);
        assertThat(offset3.compareTo(offset1)).isGreaterThan(0);
    }
}
```

```java
// SseParserTest.java
class SseParserTest {
    
    @Test
    void shouldParseDataEvent() throws Exception {
        var input = """
            event: data
            data: hello world
            
            """;
        
        var parser = new SseParser(
            new ByteArrayInputStream(input.getBytes()));
        
        var event = parser.next();
        
        assertThat(event.type()).isEqualTo("data");
        assertThat(event.data()).isEqualTo("hello world");
    }
    
    @Test
    void shouldParseControlEvent() throws Exception {
        var input = """
            event: control
            data: {"streamNextOffset":"abc123","streamCursor":"xyz"}
            
            """;
        
        var parser = new SseParser(
            new ByteArrayInputStream(input.getBytes()));
        
        var event = parser.next();
        
        assertThat(event.type()).isEqualTo("control");
        assertThat(event.data()).contains("streamNextOffset");
    }
    
    @Test
    void shouldHandleMultilineData() throws Exception {
        var input = """
            event: data
            data: line1
            data: line2
            data: line3
            
            """;
        
        var parser = new SseParser(
            new ByteArrayInputStream(input.getBytes()));
        
        var event = parser.next();
        
        assertThat(event.data()).isEqualTo("line1\nline2\nline3");
    }
    
    @Test
    void shouldReturnNullOnStreamEnd() throws Exception {
        var input = "";
        
        var parser = new SseParser(
            new ByteArrayInputStream(input.getBytes()));
        
        assertThat(parser.next()).isNull();
    }
}
```

### Client 모듈 테스트

```java
// JdkDurableStreamsClientTest.java
class JdkDurableStreamsClientTest {
    
    private MockWebServer mockServer;
    private DurableStreamsClient client;
    
    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        
        client = DurableStreamsClient.create();
    }
    
    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }
    
    @Test
    void shouldCreateStream() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(201)
            .addHeader("Stream-Next-Offset", "0")
            .addHeader("Location", mockServer.url("/test").toString()));
        
        var result = client.create(new CreateRequest(
            mockServer.url("/test").uri(),
            "application/json",
            null, null, null));
        
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.nextOffset().value()).isEqualTo("0");
        
        var request = mockServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("PUT");
    }
    
    @Test
    void shouldAppendData() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(204)
            .addHeader("Stream-Next-Offset", "abc123"));
        
        var result = client.append(new AppendRequest(
            mockServer.url("/test").uri(),
            "application/json",
            null,
            "{\"key\":\"value\"}".getBytes()));
        
        assertThat(result.status()).isEqualTo(204);
        assertThat(result.nextOffset().value()).isEqualTo("abc123");
    }
    
    @Test
    void shouldRejectEmptyAppend() {
        assertThatThrownBy(() -> client.append(new AppendRequest(
            mockServer.url("/test").uri(),
            "application/json",
            null,
            new byte[0])))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");
    }
    
    @Test
    void shouldReadCatchUp() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .addHeader("Stream-Next-Offset", "xyz789")
            .addHeader("Stream-Up-To-Date", "true")
            .setBody("[{\"event\":\"test\"}]"));
        
        var result = client.readCatchUp(new ReadRequest(
            mockServer.url("/test").uri(),
            Offset.beginning(),
            null));
        
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.upToDate()).isTrue();
        assertThat(new String(result.body())).contains("event");
    }
    
    @Test
    void shouldHandleLongPollTimeout() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(204)
            .addHeader("Stream-Next-Offset", "abc123")
            .addHeader("Stream-Up-To-Date", "true")
            .addHeader("Stream-Cursor", "1000"));
        
        // Long-poll 구독 후 첫 이벤트 확인
        var events = new ArrayList<StreamEvent>();
        var publisher = client.subscribeLongPoll(new LiveLongPollRequest(
            mockServer.url("/test").uri(),
            Offset.beginning(),
            Duration.ofSeconds(5),
            null, null));
        
        publisher.subscribe(new Flow.Subscriber<StreamEvent>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(1);
            }
            
            @Override
            public void onNext(StreamEvent event) {
                events.add(event);
            }
            
            @Override
            public void onError(Throwable t) {}
            
            @Override
            public void onComplete() {}
        });
        
        Thread.sleep(100);
        
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.UpToDate.class);
    }
}
```

### Server 모듈 테스트

```java
// InMemoryStreamStoreTest.java
class InMemoryStreamStoreTest {
    
    private InMemoryStreamStore store;
    
    @BeforeEach
    void setUp() {
        store = new InMemoryStreamStore();
    }
    
    @Test
    void shouldCreateStream() {
        var config = new StreamConfig("application/json", null, null);
        
        var result = store.createIfAbsent(
            URI.create("/test"), config, null);
        
        assertThat(result.url()).isEqualTo(URI.create("/test"));
        assertThat(result.contentType()).isEqualTo("application/json");
        assertThat(result.alreadyExisted()).isFalse();
    }
    
    @Test
    void shouldReturnExistingStreamWithSameConfig() {
        var config = new StreamConfig("application/json", null, null);
        
        store.createIfAbsent(URI.create("/test"), config, null);
        var result = store.createIfAbsent(URI.create("/test"), config, null);
        
        assertThat(result.alreadyExisted()).isTrue();
        assertThat(result.configMatches(config)).isTrue();
    }
    
    @Test
    void shouldAppendAndRead() {
        var config = new StreamConfig("text/plain", null, null);
        store.createIfAbsent(URI.create("/test"), config, null);
        
        var appendResult = store.append(
            URI.create("/test"), 
            "text/plain", 
            null, 
            "hello".getBytes());
        
        var readResult = store.read(
            URI.create("/test"), 
            "-1", 
            1024);
        
        assertThat(new String(readResult.data())).isEqualTo("hello");
        assertThat(readResult.upToDate()).isTrue();
    }
    
    @Test
    void shouldGenerateIncreasingOffsets() {
        var config = new StreamConfig("text/plain", null, null);
        store.createIfAbsent(URI.create("/test"), config, null);
        
        var offset1 = store.append(URI.create("/test"), "text/plain", null, "a".getBytes())
            .nextOffset();
        var offset2 = store.append(URI.create("/test"), "text/plain", null, "b".getBytes())
            .nextOffset();
        var offset3 = store.append(URI.create("/test"), "text/plain", null, "c".getBytes())
            .nextOffset();
        
        // Lexicographically increasing
        assertThat(offset1.value().compareTo(offset2.value())).isLessThan(0);
        assertThat(offset2.value().compareTo(offset3.value())).isLessThan(0);
    }
    
    @Test
    void shouldWaitForData() throws Exception {
        var config = new StreamConfig("text/plain", null, null);
        store.createIfAbsent(URI.create("/test"), config, null);
        
        var appendResult = store.append(
            URI.create("/test"), "text/plain", null, "initial".getBytes());
        
        // 별도 스레드에서 데이터 추가
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(() -> {
            store.append(URI.create("/test"), "text/plain", null, "new data".getBytes());
        }, 100, TimeUnit.MILLISECONDS);
        
        // waitForData 호출
        var result = store.waitForData(
            URI.create("/test"), 
            appendResult.nextOffset().value(), 
            Duration.ofSeconds(5));
        
        assertThat(result).isPresent();
        assertThat(new String(result.get().data())).isEqualTo("new data");
        
        executor.shutdown();
    }
}
```

```java
// CursorPolicyTest.java
class CursorPolicyTest {
    
    private IntervalCursorPolicy policy;
    
    @BeforeEach
    void setUp() {
        policy = new IntervalCursorPolicy();
    }
    
    @Test
    void shouldGenerateCursor() {
        var cursor = policy.nextCursor(null);
        
        assertThat(cursor).isNotNull();
        assertThat(cursor).matches("\\d+");
    }
    
    @Test
    void shouldNeverGoBackwards() {
        var cursor1 = policy.nextCursor(null);
        var cursor2 = policy.nextCursor(null);
        
        assertThat(Long.parseLong(cursor2))
            .isGreaterThanOrEqualTo(Long.parseLong(cursor1));
    }
    
    @Test
    void shouldAddJitterWhenClientCursorIsCurrent() {
        // 현재 interval 계산
        var currentCursor = policy.nextCursor(null);
        
        // 같은 cursor로 다시 요청
        var nextCursor = policy.nextCursor(currentCursor);
        
        // jitter로 인해 더 큰 값이어야 함
        assertThat(Long.parseLong(nextCursor))
            .isGreaterThan(Long.parseLong(currentCursor));
    }
}
```

---

## Integration Tests

### 클라이언트-서버 통합 테스트

```java
// ClientServerIntegrationTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientServerIntegrationTest {
    
    @LocalServerPort
    private int port;
    
    private DurableStreamsClient client;
    
    @BeforeEach
    void setUp() {
        client = DurableStreamsClient.create();
    }
    
    private URI streamUrl(String path) {
        return URI.create("http://localhost:" + port + path);
    }
    
    @Test
    void shouldCompleteFullCycle() throws Exception {
        var url = streamUrl("/test/cycle");
        
        // 1. Create
        var createResult = client.create(new CreateRequest(
            url, "application/json", null, null, null));
        assertThat(createResult.status()).isEqualTo(201);
        
        // 2. Append
        for (int i = 0; i < 5; i++) {
            var appendResult = client.append(new AppendRequest(
                url, "application/json", null,
                ("{\"seq\":" + i + "}").getBytes()));
            assertThat(appendResult.status()).isEqualTo(204);
        }
        
        // 3. Read catch-up
        var readResult = client.readCatchUp(new ReadRequest(
            url, Offset.beginning(), null));
        assertThat(readResult.status()).isEqualTo(200);
        assertThat(readResult.upToDate()).isTrue();
        assertThat(new String(readResult.body())).contains("\"seq\"");
        
        // 4. Head
        var headResult = client.head(url);
        assertThat(headResult.status()).isEqualTo(200);
        assertThat(headResult.contentType()).isEqualTo("application/json");
        
        // 5. Delete
        client.delete(url);
        
        // 6. Verify deleted
        assertThatThrownBy(() -> client.head(url))
            .isInstanceOf(Exception.class);
    }
    
    @Test
    void shouldSubscribeLongPoll() throws Exception {
        var url = streamUrl("/test/longpoll");
        
        client.create(new CreateRequest(
            url, "text/plain", null, null, null));
        
        var events = new CopyOnWriteArrayList<StreamEvent>();
        var latch = new CountDownLatch(2);
        
        var publisher = client.subscribeLongPoll(new LiveLongPollRequest(
            url, Offset.beginning(), Duration.ofSeconds(10), null, null));
        
        publisher.subscribe(new Flow.Subscriber<StreamEvent>() {
            private Flow.Subscription subscription;
            
            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }
            
            @Override
            public void onNext(StreamEvent event) {
                events.add(event);
                latch.countDown();
            }
            
            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }
            
            @Override
            public void onComplete() {}
        });
        
        // 첫 번째 UpToDate (빈 스트림)
        Thread.sleep(100);
        
        // 데이터 추가
        client.append(new AppendRequest(
            url, "text/plain", null, "hello".getBytes()));
        
        // 이벤트 수신 대기
        latch.await(5, TimeUnit.SECONDS);
        
        assertThat(events).hasSizeGreaterThanOrEqualTo(1);
    }
    
    @Test
    void shouldSubscribeSse() throws Exception {
        var url = streamUrl("/test/sse");
        
        client.create(new CreateRequest(
            url, "text/plain", null, null, "initial".getBytes()));
        
        var events = new CopyOnWriteArrayList<StreamEvent>();
        var latch = new CountDownLatch(2);
        
        var publisher = client.subscribeSse(new LiveSseRequest(
            url, Offset.beginning()));
        
        publisher.subscribe(new Flow.Subscriber<StreamEvent>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            
            @Override
            public void onNext(StreamEvent event) {
                events.add(event);
                latch.countDown();
            }
            
            @Override
            public void onError(Throwable t) {}
            
            @Override
            public void onComplete() {}
        });
        
        latch.await(5, TimeUnit.SECONDS);
        
        // Data와 Control 이벤트 모두 수신
        assertThat(events.stream()
            .filter(e -> e instanceof StreamEvent.Data)
            .count()).isGreaterThanOrEqualTo(1);
        
        assertThat(events.stream()
            .filter(e -> e instanceof StreamEvent.Control)
            .count()).isGreaterThanOrEqualTo(1);
    }
}
```

---

## 테스트 도구

### MockWebServer 설정

```kotlin
// build.gradle.kts (durable-streams-testkit)
dependencies {
    api("com.squareup.okhttp3:mockwebserver:4.12.0")
    api("org.junit.jupiter:junit-jupiter:5.10.2")
    api("org.assertj:assertj-core:3.25.3")
}
```

### 테스트 유틸리티

```java
// TestStreamFactory.java
public class TestStreamFactory {
    
    public static MockResponse successfulCreate(String offset) {
        return new MockResponse()
            .setResponseCode(201)
            .addHeader("Stream-Next-Offset", offset)
            .addHeader("Content-Type", "application/json");
    }
    
    public static MockResponse successfulAppend(String offset) {
        return new MockResponse()
            .setResponseCode(204)
            .addHeader("Stream-Next-Offset", offset);
    }
    
    public static MockResponse successfulRead(String body, String nextOffset, boolean upToDate) {
        var response = new MockResponse()
            .setResponseCode(200)
            .addHeader("Stream-Next-Offset", nextOffset)
            .addHeader("Content-Type", "application/json")
            .setBody(body);
        
        if (upToDate) {
            response.addHeader("Stream-Up-To-Date", "true");
        }
        
        return response;
    }
    
    public static MockResponse longPollTimeout(String offset, String cursor) {
        return new MockResponse()
            .setResponseCode(204)
            .addHeader("Stream-Next-Offset", offset)
            .addHeader("Stream-Up-To-Date", "true")
            .addHeader("Stream-Cursor", cursor);
    }
}
```

---

## 커버리지 목표

| 모듈 | 목표 커버리지 |
|------|--------------|
| durable-streams-core | 90%+ |
| durable-streams-client-jdk | 85%+ |
| durable-streams-server-spi | 90%+ |
| durable-streams-server-core | 85%+ |
| Framework 통합 모듈 | 75%+ |

### JaCoCo 설정

```kotlin
// build.gradle.kts
plugins {
    jacoco
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}
```

---

## 성능 테스트

### 벤치마크 (JMH)

```java
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
public class ClientBenchmark {
    
    private DurableStreamsClient client;
    private URI streamUrl;
    
    @Setup
    public void setup() {
        client = DurableStreamsClient.create();
        streamUrl = URI.create("http://localhost:8787/benchmark");
        
        client.create(new CreateRequest(
            streamUrl, "application/json", null, null, null));
    }
    
    @Benchmark
    public void appendSmallMessage() throws Exception {
        client.append(new AppendRequest(
            streamUrl, 
            "application/json", 
            null,
            "{\"key\":\"value\"}".getBytes()));
    }
    
    @Benchmark
    public void readCatchUp() throws Exception {
        client.readCatchUp(new ReadRequest(
            streamUrl, 
            Offset.beginning(), 
            null));
    }
}
```
