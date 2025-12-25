# Protocol Summary

Durable Streams 프로토콜의 핵심 요약입니다.

**Full Specification**: https://github.com/durable-streams/durable-streams/blob/main/PROTOCOL.md

---

## 프로토콜 개요

Durable Streams는 HTTP 기반의 append-only 바이트 스트림 프로토콜입니다.

### 핵심 특징

- **Durable**: 쓰기가 완료되면 삭제/만료 전까지 지속
- **Append-only**: 기존 데이터 수정 불가, 추가만 가능
- **Offset-based**: 임의 위치에서 재개 가능
- **HTTP-native**: 표준 HTTP 메서드/헤더 사용
- **CDN-friendly**: 캐싱 및 request collapsing 지원

---

## HTTP Operations

### Create Stream (PUT)

```http
PUT {stream-url}
Content-Type: application/json
Stream-TTL: 3600

[optional initial body]
```

**Response Codes**:
- `201 Created`: 새 스트림 생성
- `200 OK` / `204 No Content`: 동일 설정으로 이미 존재
- `409 Conflict`: 다른 설정으로 이미 존재

**Response Headers**:
- `Location: {stream-url}`
- `Content-Type: {stream-content-type}`
- `Stream-Next-Offset: {offset}`

---

### Append (POST)

```http
POST {stream-url}
Content-Type: application/json
Stream-Seq: seq001

{"event": "user.created"}
```

**Validation Rules**:
- Empty body → `400 Bad Request`
- Content-Type mismatch → `409 Conflict`
- Stream-Seq regression → `409 Conflict`

**Response Headers**:
- `Stream-Next-Offset: {offset}`

---

### Read - Catch-up (GET)

```http
GET {stream-url}?offset={offset}
If-None-Match: "etag-value"
```

**Response Headers**:
- `Content-Type: {stream-content-type}`
- `Stream-Next-Offset: {next-offset}`
- `Stream-Up-To-Date: true` (tail 도달 시만)
- `ETag: "{stream-id}:{start}:{end}"`
- `Cache-Control: public, max-age=60`

**Response Codes**:
- `200 OK`: 데이터 반환
- `304 Not Modified`: ETag 매칭
- `410 Gone`: Offset이 retention 범위 이전

---

### Read - Long-poll (GET)

```http
GET {stream-url}?offset={offset}&live=long-poll&cursor={cursor}
```

**Response (Data Available)**:
- `200 OK` + body + headers

**Response (Timeout)**:
```http
HTTP/1.1 204 No Content
Stream-Next-Offset: {offset}
Stream-Up-To-Date: true
Stream-Cursor: {cursor}
```

---

### Read - SSE (GET)

```http
GET {stream-url}?offset={offset}&live=sse
Accept: text/event-stream
```

**제한사항**:
- 스트림 content-type이 `text/*` 또는 `application/json`만 허용

**Event Format**:
```
event: data
data: {"key": "value"}

event: control
data: {"streamNextOffset":"abc123","streamCursor":"xyz"}

```

**Connection Lifecycle**:
- 서버: ~60초마다 연결 종료 (SHOULD)
- 클라이언트: 마지막 `streamNextOffset`으로 재연결 (MUST)

---

### Head (HEAD)

```http
HEAD {stream-url}
```

**Response Headers**:
- `Content-Type: {stream-content-type}`
- `Stream-Next-Offset: {tail-offset}`
- `Stream-TTL: {remaining-seconds}` (optional)
- `Stream-Expires-At: {rfc3339}` (optional)

---

### Delete (DELETE)

```http
DELETE {stream-url}
```

**Response**: `204 No Content`

---

## Offsets

### 특성

| 속성 | 설명 |
|------|------|
| Opaque | 클라이언트는 내부 구조 해석 금지 |
| Lexicographically Sortable | 문자열 비교로 순서 결정 |
| Strictly Increasing | 중복/역행 불가 |
| Persistent | 스트림 수명 동안 유효 |

### Sentinel Value

- `"-1"`: 스트림 시작 위치
- `?offset=-1` = `?offset` 생략과 동일

### 금지 문자

`,` `&` `=` `?` (URL 쿼리 파라미터 충돌 방지)

### 권장 길이

256자 미만 (SHOULD)

---

## Headers Reference

### Request Headers

| Header | 사용 | 설명 |
|--------|------|------|
| `Content-Type` | PUT, POST | 스트림/추가 데이터의 MIME 타입 |
| `Stream-TTL` | PUT | 상대 만료 시간 (초) |
| `Stream-Expires-At` | PUT | 절대 만료 시간 (RFC 3339) |
| `Stream-Seq` | POST | 쓰기 순서 제어 (lexicographic) |
| `If-None-Match` | GET | ETag 기반 캐시 검증 |

### Response Headers

| Header | 사용 | 설명 |
|--------|------|------|
| `Stream-Next-Offset` | 모든 성공 응답 | 다음 읽기/쓰기 위치 |
| `Stream-Up-To-Date` | GET | `true`면 tail 도달 |
| `Stream-Cursor` | GET (live) | CDN collapsing용 커서 |
| `Stream-TTL` | HEAD | 남은 TTL (초) |
| `Stream-Expires-At` | HEAD | 절대 만료 시간 |
| `ETag` | GET | 캐시 검증용 엔티티 태그 |
| `Cache-Control` | GET | 캐시 정책 |

---

## JSON Mode

### Content-Type: application/json

**Array Flattening** (POST):
```
POST body: {"a":1}          → stores: {"a":1}
POST body: [{"a":1},{"b":2}] → stores: {"a":1}, {"b":2}
POST body: [[1,2],[3,4]]     → stores: [1,2], [3,4]
POST body: []                → 400 Bad Request
```

**Response Format** (GET):
```json
[{"a":1},{"b":2}]
```

---

## Caching & Collapsing

### Cache-Control

**Catch-up reads**:
```
Cache-Control: public, max-age=60, stale-while-revalidate=300
```

**HEAD**:
```
Cache-Control: no-store
```

### Query Parameter Ordering

캐시 hit율 향상을 위해 key를 lexicographic 정렬:

```
Before: ?live=long-poll&offset=abc&cursor=xyz
After:  ?cursor=xyz&live=long-poll&offset=abc
```

### Cursor Algorithm

**Interval 기반**:
- Epoch: 2024-10-09T00:00:00Z
- Interval: 20초

**Jitter 추가**:
- 클라이언트 cursor ≥ 현재 interval → 1-3600초 jitter 추가

---

## Error Codes

| Code | 상황 |
|------|------|
| 400 | 잘못된 요청 (empty body, invalid offset, TTL+Expires-At 동시 지정) |
| 404 | 스트림 없음 |
| 405/501 | 지원하지 않는 operation |
| 409 | 충돌 (설정 불일치, Content-Type 불일치, Seq 역행) |
| 410 | Offset이 retention 이전 |
| 413 | Payload 크기 초과 |
| 429 | Rate limit |

---

## State Machine

### Client Read Flow

```
┌─────────┐
│  START  │
└────┬────┘
     │
     ▼
┌─────────┐     ┌──────────────┐
│CATCH_UP │────▶│ fetch chunk  │
└────┬────┘     └──────┬───────┘
     │                 │
     │    Up-To-Date   │  more data
     │    = false      │
     │         ◀───────┘
     │
     │ Up-To-Date = true
     ▼
┌─────────┐
│  LIVE   │
└────┬────┘
     │
     ├─── long-poll ───▶ [wait → emit → reconnect]
     │
     └─── sse ─────────▶ [stream → emit → reconnect ~60s]
```

---

## Quick Reference

### 스트림 생성 후 tail까지 읽기

```bash
# 1. Create
curl -X PUT https://server/stream/test -H "Content-Type: application/json"

# 2. Append
curl -X POST https://server/stream/test \
  -H "Content-Type: application/json" \
  -d '{"event":"test"}'

# 3. Read (catch-up)
offset="-1"
while true; do
  response=$(curl -s -D - "https://server/stream/test?offset=$offset")
  
  # Extract headers
  next_offset=$(echo "$response" | grep -i "Stream-Next-Offset" | cut -d: -f2 | tr -d ' \r')
  up_to_date=$(echo "$response" | grep -i "Stream-Up-To-Date" | cut -d: -f2 | tr -d ' \r')
  
  offset=$next_offset
  
  if [ "$up_to_date" = "true" ]; then
    break
  fi
done
```

### 실시간 구독 (Long-poll)

```bash
offset="<last-offset>"
cursor=""

while true; do
  response=$(curl -s -D - \
    "https://server/stream/test?offset=$offset&live=long-poll&cursor=$cursor")
  
  status=$(echo "$response" | head -1 | cut -d' ' -f2)
  
  if [ "$status" = "204" ]; then
    # Timeout - update offset/cursor and retry
    offset=$(echo "$response" | grep -i "Stream-Next-Offset" | cut -d: -f2 | tr -d ' \r')
    cursor=$(echo "$response" | grep -i "Stream-Cursor" | cut -d: -f2 | tr -d ' \r')
    continue
  fi
  
  # Process data...
  offset=$(echo "$response" | grep -i "Stream-Next-Offset" | cut -d: -f2 | tr -d ' \r')
  cursor=$(echo "$response" | grep -i "Stream-Cursor" | cut -d: -f2 | tr -d ' \r')
done
```

---

## 참고 자료

- [Full Protocol Specification](https://github.com/durable-streams/durable-streams/blob/main/PROTOCOL.md)
- [Reference Implementation (TypeScript)](https://github.com/durable-streams/durable-streams)
- [Conformance Tests](https://github.com/durable-streams/durable-streams/tree/main/packages/conformance-tests)
