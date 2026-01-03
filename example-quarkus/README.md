# Example: Quarkus Durable Streams server

This module is a minimal Durable Streams server implementation using Quarkus and `durable-streams-quarkus`.
It exists primarily to run the protocol conformance suite.

## Requirements

- JDK 17+ (JDK 21 recommended)
- Node.js (only needed for conformance tests)

## Run

From the repo root:

```bash
./gradlew :example-quarkus:quarkusRun
```

The server listens on `http://127.0.0.1:4432`.

- Port config: `src/main/resources/application.properties` (`quarkus.http.port`)

## Conformance

From the repo root:

```bash
npm ci --prefix conformance-node
npm --prefix conformance-node run test:quarkus
```

Override the target URL (optional):

```bash
STREAM_URL=http://127.0.0.1:4432 npm --prefix conformance-node run test:quarkus
```
