# Implementation Guide

Durable Streams Java 구현체의 상세 구현 가이드입니다.

## 핵심 프로토콜 요구사항

### 1. Offset 규칙

**MUST 요구사항**:
- Opaque: 클라이언트는 offset 구조를 해석하지 않음
- Lexicographically sortable: 문자열 비교로 순서 결정
- Strictly increasing: 중복/역행 불가
- Sentinel `-1`: 스트림 시작 위치

**금지 문자**: `,` `&` `=` `?`

```java
public record Offset(String value) {
    private static final Pattern FORBIDDEN = Pattern.compile("[,&=?]");
    
    public Offset {
        Objects.requireNonNull(value, "offset must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("offset must not be empty");
        }
        if (FORBIDDEN.matcher(value).find()) {
            throw new IllegalArgumentException(
                "offset contains forbidden characters: , & = ?");
        }
        // 256자 권장 (SHOULD)
        if (value.length() > 256) {
            // log warning
        }
    }
    
    public static Offset beginning() {
        return new Offset("-1");
    }
    
    public int compareTo(Offset other) {
        return this.value.compareTo(other.value);
    }
}
```

### 2. Create (PUT)

**Idempotency 규칙**:
- 스트림이 없으면: `201 Created`
- 동일 설정으로 존재: `200 OK` 또는 `204 No Content`
- 다른 설정으로 존재: `409 Conflict`

**TTL/Expires-At 동시 지정 금지**:
- 둘 다 제공 시 `400 Bad Request` (SHOULD)

```java
public HttpResponse handleCreate(HttpRequest request) {
    URI streamUrl = request.uri();
    
    String contentType = request.header("Content-Type")
        .orElse("application/octet-stream");
    
    Optional<String> ttl = request.header(Protocol.H_STREAM_TTL);
    Optional<String> expiresAt = request.header(Protocol.H_STREAM_EXPIRES_AT);
    
    // TTL과 Expires-At 동시 지정 체크
    if (ttl.isPresent() && expiresAt.isPresent()) {
        return HttpResponse.badRequest(
            "Cannot specify both Stream-TTL and Stream-Expires-At");
    }
    
    StreamConfig config = new StreamConfig(
        contentType,
        ttl.map(Long::parseLong).map(Duration::ofSeconds).orElse(null),
        expiresAt.map(Instant::parse).orElse(null)
    );
    
    byte[] initialBody = request.body();
    
    try {
        StreamInfo result = store.createIfAbsent(streamUrl, config, initialBody);
        
        // 이미 존재하고 설정이 같으면 200, 다르면 409
        if (result.alreadyExisted()) {
            if (result.configMatches(config)) {
                return HttpResponse.ok()
                    .header(Protocol.H_STREAM_NEXT_OFFSET, result.tailOffset().value())
                    .build();
            } else {
                return HttpResponse.conflict("Stream exists with different configuration");
            }
        }
        
        return HttpResponse.created()
            .header("Location", streamUrl.toString())
            .header("Content-Type", contentType)
            .header(Protocol.H_STREAM_NEXT_OFFSET, result.tailOffset().value())
            .build();
            
    } catch (Exception e) {
        return HttpResponse.serverError(e.getMessage());
    }
}
```

### 3. Append (POST)

**MUST 요구사항**:
- Empty body는 `400 Bad Request`
- Content-Type 불일치는 `409 Conflict`
- Stream-Seq 역행은 `409 Conflict`

```java
public HttpResponse handleAppend(HttpRequest request) {
    URI streamUrl = request.uri();
    
    byte[] body = request.body();
    if (body == null || body.length == 0) {
        return HttpResponse.badRequest("Empty body not allowed");
    }
    
    String contentType = request.header("Content-Type")
        .orElseThrow(() -> new IllegalArgumentException("Content-Type required"));
    
    Optional<String> streamSeq = request.header(Protocol.H_STREAM_SEQ);
    
    try {
        AppendOutcome result = store.append(
            streamUrl, 
            contentType, 
            streamSeq.orElse(null), 
            body
        );
        
        return HttpResponse.noContent()
            .header(Protocol.H_STREAM_NEXT_OFFSET, result.nextOffset().value())
            .build();
            
    } catch (ContentTypeMismatchException e) {
        return HttpResponse.conflict("Content-Type mismatch");
    } catch (SequenceRegressionException e) {
        return HttpResponse.conflict("Stream-Seq must be strictly increasing");
    } catch (StreamNotFoundException e) {
        return HttpResponse.notFound("Stream not found");
    }
}
```

### 4. Read - Catch-up (GET)

**Stream-Up-To-Date 헤더 규칙**:
- tail까지 모든 데이터 포함 시: `Stream-Up-To-Date: true` **MUST**
- chunk size 제한으로 일부만 반환 시: 헤더 **생략** (SHOULD NOT)

```java
public HttpResponse handleRead(HttpRequest request) {
    URI streamUrl = extractStreamUrl(request.uri());
    
    String offsetParam = queryParam(request, Protocol.Q_OFFSET)
        .orElse(Protocol.OFFSET_BEGINNING);
    
    Optional<String> ifNoneMatch = request.header("If-None-Match");
    
    try {
        ReadChunk chunk = store.read(streamUrl, offsetParam, maxChunkSize);
        
        // ETag 매칭 체크
        if (ifNoneMatch.isPresent() && ifNoneMatch.get().equals(chunk.etag())) {
            return HttpResponse.notModified()
                .header("ETag", chunk.etag())
                .build();
        }
        
        var response = HttpResponse.ok()
            .header("Content-Type", chunk.contentType())
            .header(Protocol.H_STREAM_NEXT_OFFSET, chunk.nextOffset().value())
            .header("ETag", chunk.etag())
            .header("Cache-Control", "public, max-age=60, stale-while-revalidate=300");
        
        // tail 도달 시에만 Up-To-Date 헤더 추가
        if (chunk.upToDate()) {
            response.header(Protocol.H_STREAM_UP_TO_DATE, "true");
        }
        
        return response.body(chunk.data()).build();
        
    } catch (StreamNotFoundException e) {
        return HttpResponse.notFound("Stream not found");
    } catch (OffsetGoneException e) {
        return HttpResponse.gone("Offset before earliest retained position");
    }
}
```

### 5. Read - Long-poll (GET with live=long-poll)

**204 No Content 규칙** (timeout 시):
- `Stream-Next-Offset` 헤더 **MUST**
- `Stream-Up-To-Date: true` 헤더 **MUST**

**Cursor 처리**:
- 서버는 cursor 생성 **MUST**
- 클라이언트는 cursor echo **SHOULD**

```java
public HttpResponse handleLongPoll(HttpRequest request) {
    URI streamUrl = extractStreamUrl(request.uri());
    
    String offset = queryParam(request, Protocol.Q_OFFSET)
        .orElseThrow(() -> new IllegalArgumentException("offset required for long-poll"));
    
    Optional<String> clientCursor = queryParam(request, Protocol.Q_CURSOR);
    Duration timeout = Duration.ofSeconds(30); // implementation-defined
    
    try {
        Optional<ReadChunk> result = store.waitForData(streamUrl, offset, timeout);
        
        String newCursor = cursorPolicy.nextCursor(clientCursor.orElse(null));
        
        if (result.isEmpty()) {
            // Timeout - 204 with MUST headers
            return HttpResponse.noContent()
                .header(Protocol.H_STREAM_NEXT_OFFSET, offset)
                .header(Protocol.H_STREAM_UP_TO_DATE, "true")
                .header(Protocol.H_STREAM_CURSOR, newCursor)
                .build();
        }
        
        ReadChunk chunk = result.get();
        return HttpResponse.ok()
            .header("Content-Type", chunk.contentType())
            .header(Protocol.H_STREAM_NEXT_OFFSET, chunk.nextOffset().value())
            .header(Protocol.H_STREAM_UP_TO_DATE, chunk.upToDate() ? "true" : null)
            .header(Protocol.H_STREAM_CURSOR, newCursor)
            .body(chunk.data())
            .build();
            
    } catch (StreamNotFoundException e) {
        return HttpResponse.notFound("Stream not found");
    }
}
```

### 6. Read - SSE (GET with live=sse)

**Content-Type 제한**:
- 스트림 content-type이 `text/*` 또는 `application/json`만 허용
- 다른 content-type은 `400 Bad Request`

**SSE 이벤트 형식**:
- `event: data` - 데이터 이벤트
- `event: control` - 제어 이벤트 (streamNextOffset **MUST**, streamCursor **MAY**)

**연결 관리**:
- 서버는 ~60초마다 연결 종료 **SHOULD**
- 클라이언트는 마지막 `streamNextOffset`으로 재연결 **MUST**

```java
public Flow.Publisher<SseEvent> handleSse(HttpRequest request) {
    URI streamUrl = extractStreamUrl(request.uri());
    
    String offset = queryParam(request, Protocol.Q_OFFSET)
        .orElseThrow(() -> new IllegalArgumentException("offset required for SSE"));
    
    // Content-type 체크
    String streamContentType = store.head(streamUrl).contentType();
    if (!isTextOrJson(streamContentType)) {
        throw new BadRequestException(
            "SSE only supports text/* or application/json streams");
    }
    
    return new SsePublisher(store, streamUrl, offset, cursorPolicy);
}

class SsePublisher implements Flow.Publisher<SseEvent> {
    private static final Duration CONNECTION_LIFETIME = Duration.ofSeconds(60);
    
    @Override
    public void subscribe(Flow.Subscriber<? super SseEvent> subscriber) {
        // 1. Catch-up: 기존 데이터 전송
        // 2. Live: 새 데이터 대기 및 전송
        // 3. ~60초 후 연결 종료
        
        executor.submit(() -> {
            Instant connectionStart = Instant.now();
            String currentOffset = initialOffset;
            
            while (!subscriber.isCancelled()) {
                // 연결 수명 체크
                if (Duration.between(connectionStart, Instant.now())
                        .compareTo(CONNECTION_LIFETIME) > 0) {
                    subscriber.onComplete();
                    return;
                }
                
                ReadChunk chunk = store.waitForData(streamUrl, currentOffset, 
                    Duration.ofSeconds(5));
                
                if (chunk != null && chunk.data().length > 0) {
                    // data 이벤트
                    subscriber.onNext(SseEvent.data(chunk.data()));
                    
                    // control 이벤트 (MUST)
                    String cursor = cursorPolicy.nextCursor(null);
                    subscriber.onNext(SseEvent.control(
                        chunk.nextOffset().value(), 
                        cursor
                    ));
                    
                    currentOffset = chunk.nextOffset().value();
                }
            }
        });
    }
}

record SseEvent(String type, String data) {
    public static SseEvent data(byte[] bytes) {
        return new SseEvent("data", new String(bytes, StandardCharsets.UTF_8));
    }
    
    public static SseEvent control(String nextOffset, String cursor) {
        String json = String.format(
            "{\"streamNextOffset\":\"%s\",\"streamCursor\":\"%s\"}", 
            nextOffset, cursor);
        return new SseEvent("control", json);
    }
    
    public String toSseFormat() {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }
}
```

## Cursor 정책 구현

프로토콜 스펙의 interval + jitter 규칙 구현:

```java
public class IntervalCursorPolicy implements CursorPolicy {
    // 스펙 기본값
    private static final Instant EPOCH = Instant.parse("2024-10-09T00:00:00Z");
    private static final long INTERVAL_SECONDS = 20;
    private static final int MAX_JITTER_SECONDS = 3600;
    
    private final SecureRandom random = new SecureRandom();
    private long lastIssuedInterval = -1;
    
    @Override
    public synchronized String nextCursor(String clientCursor) {
        long currentInterval = intervalOf(Instant.now());
        long cursor = Math.max(currentInterval, lastIssuedInterval);
        
        if (clientCursor != null) {
            long clientValue = parseLong(clientCursor, -1);
            
            // 클라이언트 커서가 현재 interval 이상이면 jitter 추가
            if (clientValue >= cursor) {
                int jitterSeconds = 1 + random.nextInt(MAX_JITTER_SECONDS);
                long jitterIntervals = Math.max(1, jitterSeconds / INTERVAL_SECONDS);
                cursor = clientValue + jitterIntervals;
            }
        }
        
        // 역행 방지
        if (cursor < lastIssuedInterval) {
            cursor = lastIssuedInterval;
        }
        
        lastIssuedInterval = cursor;
        return Long.toString(cursor);
    }
    
    private long intervalOf(Instant now) {
        long seconds = now.getEpochSecond() - EPOCH.getEpochSecond();
        return Math.max(0, seconds / INTERVAL_SECONDS);
    }
    
    private long parseLong(String s, long defaultValue) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
```

## JSON Mode 구현

### Array Flattening

```java
public class JsonModeHandler {
    private final ObjectMapper mapper;
    
    /**
     * POST body의 1-level array flatten
     * - 단일 객체: 그대로 저장
     * - 배열: 각 요소를 개별 메시지로 저장
     * - 빈 배열: 400 Bad Request
     */
    public List<JsonNode> flattenForAppend(byte[] body) {
        JsonNode node = mapper.readTree(body);
        
        if (node.isArray()) {
            if (node.isEmpty()) {
                throw new BadRequestException("Empty array not allowed in append");
            }
            
            List<JsonNode> messages = new ArrayList<>();
            for (JsonNode element : node) {
                messages.add(element);
            }
            return messages;
        }
        
        return List.of(node);
    }
    
    /**
     * GET 응답은 JSON array로 반환
     */
    public byte[] wrapAsArray(List<JsonNode> messages) {
        ArrayNode array = mapper.createArrayNode();
        messages.forEach(array::add);
        return mapper.writeValueAsBytes(array);
    }
}
```

## 클라이언트 상태 머신 구현

### Catch-up → Live 전환

```java
public class ClientStateMachine {
    
    public Flow.Publisher<StreamEvent> subscribe(
            DurableStreamsClient client,
            URI streamUrl,
            Offset initialOffset,
            ReadMode mode) {
        
        return new SubmissionPublisher<StreamEvent>() {
            @Override
            public void subscribe(Flow.Subscriber<? super StreamEvent> subscriber) {
                executor.submit(() -> {
                    Offset offset = initialOffset;
                    String cursor = null;
                    
                    // Phase 1: Catch-up
                    while (!isClosed()) {
                        ReadResult result = client.readCatchUp(
                            new ReadRequest(streamUrl, offset, null));
                        
                        if (result.body().length > 0) {
                            submit(new StreamEvent.Data(
                                ByteBuffer.wrap(result.body()),
                                result.contentType()));
                        }
                        
                        offset = result.nextOffset();
                        
                        // Up-to-date 확인 → live 모드 전환
                        if (result.upToDate()) {
                            submit(new StreamEvent.UpToDate(offset));
                            break;
                        }
                    }
                    
                    // Phase 2: Live
                    if (mode == ReadMode.LONG_POLL) {
                        runLongPollLoop(streamUrl, offset, cursor, subscriber);
                    } else if (mode == ReadMode.SSE) {
                        runSseLoop(streamUrl, offset, subscriber);
                    }
                });
            }
        };
    }
    
    private void runLongPollLoop(
            URI streamUrl, 
            Offset offset, 
            String cursor,
            Flow.Subscriber<? super StreamEvent> subscriber) {
        
        while (!isClosed()) {
            try {
                HttpResponse resp = client.sendLongPoll(streamUrl, offset, cursor);
                
                if (resp.statusCode() == 204) {
                    // Timeout - 헤더에서 offset/cursor 업데이트
                    offset = new Offset(resp.header(Protocol.H_STREAM_NEXT_OFFSET).get());
                    cursor = resp.header(Protocol.H_STREAM_CURSOR).orElse(cursor);
                    submit(new StreamEvent.UpToDate(offset));
                    continue;
                }
                
                if (resp.statusCode() == 304) {
                    // Not Modified
                    cursor = resp.header(Protocol.H_STREAM_CURSOR).orElse(cursor);
                    continue;
                }
                
                if (resp.statusCode() == 200) {
                    submit(new StreamEvent.Data(
                        ByteBuffer.wrap(resp.body()),
                        resp.header("Content-Type").orElse("application/octet-stream")));
                    
                    offset = new Offset(resp.header(Protocol.H_STREAM_NEXT_OFFSET).get());
                    cursor = resp.header(Protocol.H_STREAM_CURSOR).orElse(cursor);
                    
                    if (resp.header(Protocol.H_STREAM_UP_TO_DATE)
                            .map("true"::equalsIgnoreCase).orElse(false)) {
                        submit(new StreamEvent.UpToDate(offset));
                    }
                }
                
            } catch (Exception e) {
                closeExceptionally(e);
                return;
            }
        }
    }
    
    private void runSseLoop(
            URI streamUrl, 
            Offset offset,
            Flow.Subscriber<? super StreamEvent> subscriber) {
        
        while (!isClosed()) {
            try {
                HttpResponse resp = client.sendSse(streamUrl, offset);
                
                SseParser parser = new SseParser(resp.bodyInputStream());
                SseParser.Event event;
                
                while ((event = parser.next()) != null) {
                    switch (event.type()) {
                        case "data" -> submit(new StreamEvent.Data(
                            ByteBuffer.wrap(event.data()),
                            resp.header("Content-Type").orElse("text/plain")));
                            
                        case "control" -> {
                            ControlEvent ctrl = parseControl(event.data());
                            offset = new Offset(ctrl.streamNextOffset());
                            submit(new StreamEvent.Control(
                                offset, 
                                Optional.ofNullable(ctrl.streamCursor())));
                        }
                    }
                }
                
                // 서버가 연결 종료 (~60초) → 마지막 offset으로 재연결
                // offset은 마지막 control 이벤트에서 업데이트됨
                
            } catch (Exception e) {
                // 재연결 시도 (backoff 적용)
                Thread.sleep(backoffDelay());
            }
        }
    }
}
```

## SSE 파서 구현

```java
public final class SseParser {
    private final BufferedReader reader;
    
    public SseParser(InputStream is) {
        this.reader = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8));
    }
    
    public record Event(String type, String data) {
        public byte[] dataBytes() {
            return data.getBytes(StandardCharsets.UTF_8);
        }
    }
    
    public Event next() throws IOException {
        String eventType = "message"; // 기본값
        StringBuilder data = new StringBuilder();
        boolean hasContent = false;
        
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // 빈 줄 = 이벤트 종료
                if (hasContent) {
                    break;
                }
                continue;
            }
            
            hasContent = true;
            
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append("\n");
                }
                data.append(line.substring(5).trim());
            }
            // id:, retry:, 주석(:) 무시
        }
        
        if (!hasContent) {
            return null; // 스트림 종료
        }
        
        return new Event(eventType, data.toString());
    }
}
```

## Offset 생성기 구현

### ULID 기반

```java
public class UlidOffsetGenerator implements OffsetGenerator {
    private final AtomicLong lastTimestamp = new AtomicLong(0);
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final SecureRandom random = new SecureRandom();
    
    @Override
    public String generate() {
        long timestamp = System.currentTimeMillis();
        long last = lastTimestamp.get();
        
        if (timestamp <= last) {
            // 동일 밀리초 내에서 sequence 증가
            int seq = sequence.incrementAndGet();
            if (seq > 0xFFFF) {
                // Sequence overflow - 다음 밀리초까지 대기
                while (System.currentTimeMillis() <= last) {
                    Thread.onSpinWait();
                }
                timestamp = System.currentTimeMillis();
                sequence.set(0);
            }
        } else {
            lastTimestamp.set(timestamp);
            sequence.set(random.nextInt(0xFFFF));
        }
        
        return encodeUlid(timestamp, sequence.get());
    }
    
    @Override
    public boolean isValid(String offset) {
        if ("-1".equals(offset)) {
            return true;
        }
        // ULID 형식 검증
        return offset.length() == 26 && 
               offset.chars().allMatch(c -> 
                   Character.isLetterOrDigit(c) && c != 'I' && c != 'L' && c != 'O');
    }
    
    private String encodeUlid(long timestamp, int sequence) {
        // Crockford's Base32 인코딩
        // ... 구현
    }
}
```

## ETag 생성

```java
public class ETagGenerator {
    /**
     * 스펙 권장 형식: {stream_id}:{start_offset}:{end_offset}
     */
    public String generate(String streamId, String startOffset, String endOffset) {
        return String.format("\"%s:%s:%s\"", streamId, startOffset, endOffset);
    }
    
    public boolean matches(String etag, String streamId, String startOffset, String endOffset) {
        String expected = generate(streamId, startOffset, endOffset);
        return expected.equals(etag);
    }
}
```

## 에러 처리

```java
public sealed interface DurableStreamsException extends RuntimeException {
    
    // 400 Bad Request
    record BadRequest(String message) implements DurableStreamsException {}
    
    // 404 Not Found
    record StreamNotFound(URI streamUrl) implements DurableStreamsException {}
    
    // 409 Conflict
    record Conflict(String reason) implements DurableStreamsException {}
    
    // 410 Gone
    record OffsetGone(String offset) implements DurableStreamsException {}
    
    // 413 Payload Too Large
    record PayloadTooLarge(long size, long limit) implements DurableStreamsException {}
    
    // 429 Too Many Requests
    record RateLimited(Duration retryAfter) implements DurableStreamsException {}
}
```
