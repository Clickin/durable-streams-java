# Development Plan

Durable Streams Java 구현체의 개발 로드맵입니다.

## Phase 개요

```
Phase 1: Core Foundation          [Week 1-2]
Phase 2: Client Implementation    [Week 3-4]
Phase 3: Server Implementation    [Week 5-6]
Phase 4: Framework Integration    [Week 7-8]
Phase 5: Polish & Release         [Week 9-10]
```

---

## Phase 1: Core Foundation

**목표**: 프로토콜 기반 타입 시스템 완성

### Milestone 1.1: Project Setup

**Tasks**:
- [ ] Gradle 멀티모듈 프로젝트 생성
- [ ] CI/CD 파이프라인 구성 (GitHub Actions)
- [ ] Code style 설정 (Checkstyle, Spotless)
- [ ] JaCoCo 코드 커버리지 설정

**결과물**:
```
durable-streams-java/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── .github/workflows/
│   ├── ci.yml
│   └── release.yml
├── config/
│   ├── checkstyle/
│   └── spotless/
└── durable-streams-core/
    └── build.gradle.kts
```

### Milestone 1.2: Core Types

**Tasks**:
- [ ] `Protocol.java` - 헤더/쿼리 상수
- [ ] `Offset.java` - Offset 값 객체 (validation)
- [ ] `StreamEvent.java` - sealed interface
- [ ] `StreamConfig.java` - 스트림 설정
- [ ] `ReadMode.java` - enum
- [ ] `DurableStreamsException.java` - 예외 계층

**테스트**:
- [ ] Offset validation 테스트 (금지 문자, sentinel)
- [ ] StreamEvent sealed hierarchy 테스트

### Milestone 1.3: Core Utilities

**Tasks**:
- [ ] `SseParser.java` - SSE 이벤트 파싱
- [ ] `UrlBuilder.java` - 쿼리 파라미터 정렬
- [ ] `ControlJson.java` - control 이벤트 JSON 파싱 (minimal)

**테스트**:
- [ ] SSE 파싱 테스트 (data, control, 멀티라인)
- [ ] URL builder 정렬 테스트

---

## Phase 2: Client Implementation

**목표**: JDK HttpClient 기반 완전한 클라이언트

### Milestone 2.1: Sync Operations

**Tasks**:
- [ ] `DurableStreamsClient.java` - 인터페이스
- [ ] `JdkDurableStreamsClient.java` - 구현체
- [ ] `CreateRequest/Result` - PUT
- [ ] `AppendRequest/Result` - POST
- [ ] `HeadResult` - HEAD
- [ ] `ReadRequest/Result` - GET catch-up

**테스트**:
- [ ] MockWebServer 기반 단위 테스트
- [ ] 각 HTTP 메서드별 성공/실패 케이스

### Milestone 2.2: Catch-up Loop

**Tasks**:
- [ ] `CatchUpLoop.java` - chunk 반복 읽기
- [ ] `Stream-Up-To-Date` 헤더 감지
- [ ] ETag/If-None-Match 지원

**테스트**:
- [ ] 멀티 chunk 읽기 테스트
- [ ] Up-to-date 감지 테스트
- [ ] 304 Not Modified 처리 테스트

### Milestone 2.3: Long-poll Subscription

**Tasks**:
- [ ] `LiveLongPollRequest.java`
- [ ] `LongPollLoop.java` - 재연결 루프
- [ ] 204 No Content 처리
- [ ] Cursor echo 구현

**테스트**:
- [ ] Timeout (204) 처리 테스트
- [ ] Cursor echo 테스트
- [ ] 재연결 시나리오 테스트

### Milestone 2.4: SSE Subscription

**Tasks**:
- [ ] `LiveSseRequest.java`
- [ ] `SseLoop.java` - SSE 재연결 루프
- [ ] data/control 이벤트 분기
- [ ] ~60초 reconnect 구현

**테스트**:
- [ ] SSE 이벤트 파싱 테스트
- [ ] control에서 offset 업데이트 테스트
- [ ] 재연결 시나리오 테스트

---

## Phase 3: Server Implementation

**목표**: 프레임워크 독립 프로토콜 엔진

### Milestone 3.1: Server SPI

**Tasks**:
- [ ] `StreamStore.java` - 스토리지 인터페이스
- [ ] `OffsetGenerator.java` - Offset 생성
- [ ] `CursorPolicy.java` - Cursor 생성
- [ ] `ReadChunk.java` - 읽기 결과
- [ ] `StreamInfo.java` - 메타데이터
- [ ] `AppendOutcome.java` - 쓰기 결과

**테스트**:
- [ ] 인터페이스 계약 테스트

### Milestone 3.2: In-Memory Store

**Tasks**:
- [ ] `InMemoryStreamStore.java` - 테스트용 구현
- [ ] Offset 생성 (ULID 기반)
- [ ] Cursor 정책 (interval + jitter)
- [ ] TTL/Expiry 처리

**테스트**:
- [ ] CRUD 통합 테스트
- [ ] Offset monotonicity 테스트
- [ ] Cursor jitter 테스트
- [ ] TTL 만료 테스트

### Milestone 3.3: Protocol Engine

**Tasks**:
- [ ] `ProtocolEngine.java` - 핸들러 조합
- [ ] `CreateHandler.java` - PUT
- [ ] `AppendHandler.java` - POST
- [ ] `ReadHandler.java` - GET catch-up
- [ ] `LiveLongPollHandler.java` - GET long-poll
- [ ] `LiveSseHandler.java` - GET SSE
- [ ] `HeadHandler.java` - HEAD
- [ ] `DeleteHandler.java` - DELETE

**테스트**:
- [ ] 각 핸들러 단위 테스트
- [ ] 통합 시나리오 테스트

### Milestone 3.4: JSON Mode

**Tasks**:
- [ ] `durable-streams-json-jackson` 모듈
- [ ] Array flatten 로직
- [ ] JSON validation
- [ ] 빈 배열 거부

**테스트**:
- [ ] 1-level flatten 테스트
- [ ] 응답 array 형식 테스트
- [ ] 빈 배열 400 테스트

---

## Phase 4: Framework Integration

**목표**: Spring/Micronaut/Quarkus 통합

### Milestone 4.1: Reactive Adapters

**Tasks**:
- [ ] `durable-streams-reactive-adapters` 모듈
- [ ] `durable-streams-client-reactor` 모듈
- [ ] `durable-streams-client-rxjava3` 모듈

**테스트**:
- [ ] Flow ↔ Publisher 변환 테스트
- [ ] Backpressure 테스트

### Milestone 4.2: Kotlin Support

**Tasks**:
- [ ] `durable-streams-kotlin` 모듈
- [ ] suspend 함수 래퍼
- [ ] kotlinx.coroutines.flow 변환

**테스트**:
- [ ] Coroutine 통합 테스트
- [ ] Flow 수집 테스트

### Milestone 4.3: Spring WebFlux

**Tasks**:
- [ ] `durable-streams-spring-webflux` 모듈
- [ ] RouterFunction 기반 라우팅
- [ ] SSE 응답 스트리밍
- [ ] WebFlux HTTP 어댑터

**테스트**:
- [ ] WebTestClient 통합 테스트
- [ ] SSE 스트리밍 테스트

### Milestone 4.4: Spring Boot Starter

**Tasks**:
- [ ] `durable-streams-spring-boot-starter` 모듈
- [ ] AutoConfiguration
- [ ] Properties 바인딩
- [ ] Actuator 통합 (선택)

**테스트**:
- [ ] 자동 설정 테스트
- [ ] Properties 바인딩 테스트

### Milestone 4.5: Micronaut (Optional)

**Tasks**:
- [ ] `durable-streams-micronaut-server` 모듈
- [ ] @Controller 기반 구현
- [ ] SSE Publisher<Event> 반환

**테스트**:
- [ ] Micronaut 통합 테스트

### Milestone 4.6: Quarkus (Optional)

**Tasks**:
- [ ] `durable-streams-quarkus-server` 모듈
- [ ] JAX-RS Resource 구현
- [ ] Multi<OutboundSseEvent> 반환

**테스트**:
- [ ] Quarkus 통합 테스트

---

## Phase 5: Polish & Release

**목표**: Conformance 통과 및 릴리스

### Milestone 5.1: Conformance Tests

**Tasks**:
- [ ] upstream conformance test 환경 구성
- [ ] Server conformance test 실행
- [ ] Client conformance test 실행
- [ ] 실패 케이스 수정

**결과물**:
- [ ] CI에서 conformance test 자동 실행
- [ ] 모든 테스트 통과 확인

### Milestone 5.2: Documentation

**Tasks**:
- [ ] Javadoc 작성
- [ ] README.md 완성
- [ ] 예제 코드 작성
- [ ] 마이그레이션 가이드 (기존 SSE/WS 사용자)

### Milestone 5.3: Performance

**Tasks**:
- [ ] 벤치마크 작성
- [ ] 프로파일링
- [ ] 최적화 (필요 시)

### Milestone 5.4: Release

**Tasks**:
- [ ] 버전 태깅
- [ ] Maven Central 배포
- [ ] 릴리스 노트 작성

---

## 우선순위 기준

### P0 (필수)
- Core types
- Client JDK (sync + long-poll + SSE)
- Server SPI + In-memory store
- Protocol Engine
- Spring WebFlux + Boot Starter
- Conformance tests 통과

### P1 (중요)
- Kotlin coroutines
- Reactor/RxJava adapters
- JSON mode

### P2 (추후)
- Micronaut 통합
- Quarkus 통합
- Spring MVC (blocking)
- 추가 스토리지 백엔드 (PostgreSQL, S3)

---

## 리스크 및 대응

### 1. Conformance Test 실패

**리스크**: 프로토콜 해석 오류로 conformance test 실패

**대응**:
- 초기부터 conformance test 실행
- 실패 케이스별 스펙 재검토
- upstream 이슈 트래커 참조

### 2. SSE 재연결 복잡도

**리스크**: ~60초 reconnect, offset 추적 로직 복잡

**대응**:
- 상태 머신 명확히 설계
- 엣지 케이스 테스트 강화
- upstream TypeScript 클라이언트 참조

### 3. 프레임워크별 SSE 차이

**리스크**: Spring/Micronaut/Quarkus의 SSE API 차이

**대응**:
- 각 프레임워크 공식 문서 우선 참조
- event name (data/control) 지원 확인
- 필요 시 raw HTTP 응답 사용

---

## 일정 요약

| Phase | 기간 | 핵심 결과물 |
|-------|------|-------------|
| 1 | Week 1-2 | `durable-streams-core` 완성 |
| 2 | Week 3-4 | `durable-streams-client-jdk` 완성 |
| 3 | Week 5-6 | `durable-streams-server-*` 완성 |
| 4 | Week 7-8 | Spring 통합, Kotlin 지원 |
| 5 | Week 9-10 | Conformance 통과, 릴리스 |

---

## 체크포인트

### Week 2 End
- [ ] Core 모듈 테스트 통과
- [ ] SSE 파서 동작 확인

### Week 4 End
- [ ] 클라이언트 전체 기능 동작
- [ ] MockServer 기반 테스트 통과

### Week 6 End
- [ ] 서버 전체 기능 동작
- [ ] 클라이언트-서버 통합 테스트 통과

### Week 8 End
- [ ] Spring Boot Starter 동작
- [ ] Kotlin 어댑터 동작

### Week 10 End
- [ ] Conformance tests 100% 통과
- [ ] Maven Central 배포 완료
