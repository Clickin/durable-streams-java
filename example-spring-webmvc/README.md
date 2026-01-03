# Example: Spring MVC Durable Streams server

This module is a minimal Durable Streams server implementation using Spring MVC and the Servlet adapter (`durable-streams-servlet`).
It exists primarily to run the protocol conformance suite.

## Requirements

- JDK 17+ (JDK 21 recommended)
- Node.js (only needed for conformance tests)

## Run

From the repo root:

```bash
./gradlew :example-spring-webmvc:bootRun
```

The server listens on `http://127.0.0.1:4434`.

- Port config: `src/main/resources/application.yaml` (`server.port`)

## Conformance

From the repo root:

```bash
npm ci --prefix conformance-node
npm --prefix conformance-node run test:springwebmvc
```

Override the target URL (optional):

```bash
STREAM_URL=http://127.0.0.1:4434 npm --prefix conformance-node run test:springwebmvc
```
