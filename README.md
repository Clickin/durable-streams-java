# durable-streams-java

Java 17 implementation of the Durable Streams protocol.

## Built modules

- `durable-streams-core` - protocol types and helpers
- `durable-streams-client-jdk` - JDK HttpClient client
- `durable-streams-json-spi` - JSON serialization SPI (library-agnostic)
- `durable-streams-json-jackson` - Jackson implementation for JSON mode (optional)
- `durable-streams-server-spi` - server storage/policy abstractions
- `durable-streams-server-core` - protocol engine
- `durable-streams-conformance-runner` - conformance server/client runner

## Reference adapters (not built)

These directories are kept as reference examples and are excluded from `settings.gradle.kts`:

- `durable-streams-reactive-adapters`
- `durable-streams-client-reactor`
- `durable-streams-client-rxjava3`
- `durable-streams-kotlin`
- `durable-streams-spring-webflux`
- `durable-streams-spring-webmvc`
- `durable-streams-spring-webflux-starter`
- `durable-streams-spring-webmvc-starter`
- `durable-streams-spring-boot-starter`
- `durable-streams-micronaut-server`
- `durable-streams-micronaut-client`
- `durable-streams-quarkus-server`
- `durable-streams-quarkus-client`

## JSON mode

JSON mode is required by the protocol and implemented via the JSON SPI. You can use the Jackson module or provide your own codec implementation.

- Default codec discovery uses `ServiceLoader` via `StreamCodecProvider`.
- To avoid Jackson, ship your own module that implements `JsonCodec` and `StreamCodecProvider` for `application/json`.

## Reactive integration examples

### Reactor

Gradle dependencies:

```kotlin
dependencies {
    implementation("org.reactivestreams:reactive-streams-flow-adapters:1.0.2")
    implementation("io.projectreactor:reactor-core:3.7.1")
}
```

Usage:

```java
import io.durablestreams.client.jdk.DurableStreamsClient;
import io.durablestreams.client.jdk.LiveLongPollRequest;
import io.durablestreams.core.StreamEvent;
import org.reactivestreams.FlowAdapters;
import reactor.core.publisher.Flux;

DurableStreamsClient client = DurableStreamsClient.create();
Flow.Publisher<StreamEvent> pub = client.subscribeLongPoll(request);
Flux<StreamEvent> flux = Flux.from(FlowAdapters.toPublisher(pub));
```

### RxJava3

Gradle dependencies:

```kotlin
dependencies {
    implementation("org.reactivestreams:reactive-streams-flow-adapters:1.0.2")
    implementation("io.reactivex.rxjava3:rxjava:3.1.9")
}
```

Usage:

```java
import io.durablestreams.client.jdk.DurableStreamsClient;
import io.durablestreams.core.StreamEvent;
import io.reactivex.rxjava3.core.Flowable;
import org.reactivestreams.FlowAdapters;

DurableStreamsClient client = DurableStreamsClient.create();
Flow.Publisher<StreamEvent> pub = client.subscribeLongPoll(request);
Flowable<StreamEvent> flowable = Flowable.fromPublisher(FlowAdapters.toPublisher(pub));
```

### Kotlin coroutines

Gradle dependencies:

```kotlin
dependencies {
    implementation("org.reactivestreams:reactive-streams-flow-adapters:1.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.9.0")
}
```

Usage:

```kotlin
import io.durablestreams.client.jdk.DurableStreamsClient
import io.durablestreams.core.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.FlowAdapters

val client = DurableStreamsClient.create()
val pub = client.subscribeLongPoll(request)
val flow: Flow<StreamEvent> = FlowAdapters.toPublisher(pub).asFlow()
```
