# durable-streams-java (skeleton)

Gradle multi-module skeleton targeting **Java 17** (toolchain) and intended to be used with **Gradle 9.1**.

- `durable-streams-core` contains protocol-centric Java types and helpers.
- Other modules are empty placeholders (compile-ready) for later framework/client integrations.

Gradle wrapper is intentionally not included.


## Implemented in this iteration
- Server codec SPI + Jackson JSON codec module
- JDK client + Reactor/RxJava3/Kotlin coroutine adapters
- Spring WebFlux/WebMVC server adapters
- Micronaut/Quarkus server adapters (minimal)
