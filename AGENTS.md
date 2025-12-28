# Durable Streams Java Implementation

## 프로젝트 개요

Durable Streams는 HTTP 기반의 append-only 바이트 스트림 프로토콜입니다. 클라이언트 애플리케이션에 실시간 동기화를 제공하며, offset 기반 재개 기능으로 안정적인 데이터 스트리밍을 지원합니다.

이 프로젝트는 **Durable Streams 프로토콜의 Java 구현체**를 개발하는 것을 목표로 합니다.

## 핵심 원칙

### 1. Core는 프로토콜에 집중

- **의존성 최소화**: core 모듈은 JDK 표준 API만 사용
- **프레임워크 독립**: Spring, Micronaut, Quarkus 등은 별도 glue 모듈로 분리
- **Reactive 라이브러리 비포함**: Reactor/RxJava는 어댑터 모듈에서 제공

### 2. Monorepo 멀티모듈 구조

```
durable-streams-java/
├── durable-streams-core/           # 프로토콜 상수, 모델, 유틸리티
├── durable-streams-client/     # JDK HttpClient 기반 클라이언트
├── durable-streams-server-spi/     # 서버 스토리지/정책 추상화
├── durable-streams-server-core/    # 프로토콜 엔진 (프레임워크 독립)
├── durable-streams-json-jackson/   # JSON mode 지원
├── durable-streams-client-reactor/ # Reactor 어댑터
├── durable-streams-kotlin/         # Kotlin coroutine 지원
├── durable-streams-spring-webflux/ # Spring WebFlux 통합
├── durable-streams-spring-boot-starter/
├── durable-streams-micronaut/      # Micronaut 통합
├── durable-streams-quarkus/        # Quarkus 통합
└── durable-streams-testkit/        # 테스트 도구
```

### 3. Conformance Tests 활용

upstream의 언어 중립 conformance tests를 사용하여 프로토콜 준수를 검증합니다.

## 상세 문서

| 문서 | 설명 |
|------|------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | 전체 아키텍처 및 모듈 간 관계 |
| [MODULES.md](./MODULES.md) | 각 모듈의 역할과 의존성 |
| [IMPLEMENTATION.md](./IMPLEMENTATION.md) | 구현 세부사항 및 코드 가이드 |
| [PLAN.md](./PLAN.md) | 개발 로드맵 및 마일스톤 |
| [TESTING.md](./TESTING.md) | 테스트 전략 및 conformance tests |
| [PROTOCOL-SUMMARY.md](./PROTOCOL-SUMMARY.md) | 프로토콜 핵심 요약 |

## 프로토콜 핵심 요구사항

### HTTP 오퍼레이션

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `PUT` | `{stream-url}` | 스트림 생성 (idempotent) |
| `POST` | `{stream-url}` | 데이터 추가 |
| `GET` | `{stream-url}?offset=` | Catch-up 읽기 |
| `GET` | `{stream-url}?offset=&live=long-poll` | Long-poll 실시간 |
| `GET` | `{stream-url}?offset=&live=sse` | SSE 실시간 |
| `HEAD` | `{stream-url}` | 메타데이터 조회 |
| `DELETE` | `{stream-url}` | 스트림 삭제 |

### 핵심 헤더

- `Stream-Next-Offset`: 다음 읽기 위치
- `Stream-Up-To-Date`: tail 도달 여부
- `Stream-Cursor`: CDN collapsing용 커서
- `Stream-TTL` / `Stream-Expires-At`: 만료 정책
- `Stream-Seq`: 쓰기 순서 제어

### Offset 규칙

- **Opaque**: 클라이언트는 내부 구조를 해석하지 않음
- **Lexicographically sortable**: 문자열 비교로 순서 결정
- **Strictly increasing**: 중복/역행 불가
- **Sentinel `-1`**: 스트림 시작 위치

## 참조 링크

- **프로토콜 스펙**: https://github.com/durable-streams/durable-streams/blob/main/PROTOCOL.md
- **Upstream Repository**: https://github.com/durable-streams/durable-streams
- **Conformance Tests**: https://github.com/durable-streams/durable-streams/tree/main/packages/conformance-tests

## 빠른 시작

### 1. 저장소 클론

```bash
git clone https://github.com/your-org/durable-streams-java.git
cd durable-streams-java
```

### 2. 빌드

```bash
./gradlew build
```

### 3. 테스트 실행

```bash
./gradlew test
```

### 4. Conformance Tests

```bash
# 서버 시작 후
./gradlew :durable-streams-testkit:conformanceTest \
  -Pconformance.baseUrl=http://localhost:8787
```

## 기여 가이드

1. 이슈 생성 또는 기존 이슈 확인
2. 브랜치 생성: `feature/issue-number-description`
3. 구현 및 테스트 작성
4. PR 생성 (conformance tests 통과 필수)

## 라이선스

Apache 2.0
