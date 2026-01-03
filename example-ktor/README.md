# Example: Ktor Durable Streams server

This module is a minimal Durable Streams server implementation using Ktor (Netty) and `durable-streams-ktor`.
It exists primarily to run the protocol conformance suite.

## Requirements

- JDK 17+ (JDK 21 recommended)
- Node.js (only needed for conformance tests)

## Run

From the repo root:

```bash
./gradlew :example-ktor:run
```

The server listens on `http://127.0.0.1:4435`.

- Port config: `src/main/kotlin/example/ktor/Application.kt`

## Conformance

From the repo root:

```bash
npm ci --prefix conformance-node
npm --prefix conformance-node run test:ktor
```

Override the target URL (optional):

```bash
STREAM_URL=http://127.0.0.1:4435 npm --prefix conformance-node run test:ktor
```
