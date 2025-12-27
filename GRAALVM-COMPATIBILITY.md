# GraalVM Native Image Compatibility Analysis

This document analyzes the `durable-streams-java` repository for GraalVM Native Image compatibility issues, including dynamic class loading, reflection usage, and potentially unreachable code paths.

## Executive Summary

| Category | Status | Severity |
|----------|--------|----------|
| Dynamic Class Loading (`Class.forName`) | **CLEAN** | N/A |
| Reflection (Methods/Fields) | **CLEAN** | N/A |
| Proxy Usage | **CLEAN** | N/A |
| Java Serialization | **CLEAN** | N/A |
| MethodHandles/VarHandle/Unsafe | **CLEAN** | N/A |
| ServiceLoader (SPI) | **REQUIRES CONFIG** | Critical |
| Jackson ObjectMapper | **REQUIRES CONFIG** | Major |
| Sealed Classes & Records | **REQUIRES CONFIG** | Moderate |
| Spring Boot AutoConfiguration | **REQUIRES CONFIG** | Moderate |
| Micronaut/Quarkus Integration | **FRAMEWORK-DEPENDENT** | Moderate |

**Overall Assessment**: The codebase is well-designed for GraalVM compatibility with no explicit reflection or dynamic class loading. However, the SPI (ServiceLoader) pattern and Jackson JSON library require native-image configuration.

---

## Detailed Findings

### 1. ServiceLoader Usage (CRITICAL)

The project uses Java's `ServiceLoader` mechanism for pluggable codec discovery. This is problematic for GraalVM native images because services are discovered at runtime.

**Affected Files:**

| File | Line | Code |
|------|------|------|
| `durable-streams-server-core/.../ServiceLoaderCodecRegistry.java` | 26 | `ServiceLoader.load(StreamCodecProvider.class, cl)` |
| `durable-streams-server-spi/.../ReferenceStreamStore.java` | 123 | `ServiceLoader.load(StreamCodecProvider.class)` |

**Service Provider Files:**
- `durable-streams-json-jackson/src/main/resources/META-INF/services/io.durablestreams.server.spi.StreamCodecProvider`

**Required Configuration:**

Create `META-INF/native-image/io.durablestreams/resource-config.json`:
```json
{
  "resources": {
    "includes": [
      {"pattern": "META-INF/services/io.durablestreams.server.spi.StreamCodecProvider"}
    ]
  }
}
```

Create `META-INF/native-image/io.durablestreams/reflect-config.json`:
```json
[
  {
    "name": "io.durablestreams.json.jackson.JacksonJsonCodecProvider",
    "methods": [{"name": "<init>", "parameterTypes": []}]
  },
  {
    "name": "io.durablestreams.json.jackson.JacksonJsonStreamCodec",
    "methods": [{"name": "<init>", "parameterTypes": []}]
  }
]
```

**Alternative Solution**: Consider providing a compile-time alternative that doesn't use ServiceLoader:

```java
public static ServiceLoaderCodecRegistry withExplicitCodecs(StreamCodecProvider... providers) {
    // Explicit registration without ServiceLoader
}
```

---

### 2. Jackson ObjectMapper Usage (MAJOR)

Jackson relies heavily on reflection for JSON serialization/deserialization. The project uses Jackson in the `durable-streams-json-jackson` module.

**Affected Files:**

| File | Lines | Usage |
|------|-------|-------|
| `JacksonJsonCodec.java` | 20, 26, 33 | `ObjectMapper` field and construction |
| `JacksonJsonStreamCodec.java` | 35 | Static `ObjectMapper` instance |
| `JacksonJsonCodec.java` | 92, 102 | `TypeFactory.constructCollectionType()` |

**Key Observations:**
- Uses `ObjectMapper.readValue()` and `writeValueAsString()`
- Uses `JavaType` for generic collection deserialization
- Uses `JsonNode` tree model (less reflection-intensive)

**Required Configuration:**

For any user-defined types being serialized/deserialized, reflection configuration is needed:

```json
[
  {
    "name": "com.example.YourEventType",
    "allDeclaredFields": true,
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  }
]
```

**Jackson-specific classes (internal):**
```json
[
  {"name": "com.fasterxml.jackson.databind.JsonNode", "allPublicMethods": true},
  {"name": "com.fasterxml.jackson.databind.node.ObjectNode", "allPublicMethods": true},
  {"name": "com.fasterxml.jackson.databind.node.ArrayNode", "allPublicMethods": true}
]
```

**Note**: Jackson 2.15+ has improved GraalVM support. Consider using `jackson-module-blackbird` instead of reflection-based access.

---

### 3. Sealed Classes and Records (MODERATE)

Java 17+ sealed classes and records are used throughout the codebase. GraalVM 21+ handles these well, but they still need registration for reflection access.

**Sealed Interfaces:**

| Interface | Location | Permitted Types |
|-----------|----------|-----------------|
| `StreamEvent` | `durable-streams-core` | `Data`, `Control`, `UpToDate` |
| `ResponseBody` | `durable-streams-server-core` | `Empty`, `Bytes`, `Sse` |
| `RateLimiter.Result` | `durable-streams-server-spi` | `Allowed`, `Rejected` |

**Records Found (18 total):**

Core module:
- `SseParser.Event`
- `StreamEvent.Data`, `StreamEvent.Control`, `StreamEvent.UpToDate`
- `ControlJson.Control`

Client module:
- `LiveSseRequest`, `AppendRequest`, `CreateRequest`, `ReadRequest`, `LiveLongPollRequest`
- `AppendResult`, `CreateResult`, `ReadResult`, `HeadResult`

Server module:
- `ResponseBody.Empty`, `ResponseBody.Bytes`, `ResponseBody.Sse`
- `RateLimiter.Result.Allowed`, `RateLimiter.Result.Rejected`
- `StreamCodec.ReadChunk`

**Required Configuration** (if accessed via reflection):
```json
[
  {
    "name": "io.durablestreams.core.StreamEvent$Data",
    "allDeclaredFields": true,
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true
  }
]
```

**Note**: Records work correctly in GraalVM without reflection config if only used via constructors and accessors directly. Config is only needed if:
- Serialized/deserialized via Jackson
- Accessed via reflection APIs
- Used with frameworks that use reflection

---

### 4. Spring Boot AutoConfiguration (MODERATE)

The Spring Boot starters use conditional annotations that rely on reflection.

**Affected Files:**

| File | Annotations Used |
|------|------------------|
| `DurableStreamsWebFluxAutoConfiguration.java` | `@AutoConfiguration`, `@Bean`, `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnWebApplication` |
| `DurableStreamsWebMvcAutoConfiguration.java` | Same as above |
| `DurableStreamsAutoConfiguration.java` | Same as above |

**Spring Boot Resources:**
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (3 files)

**Required Configuration:**

Spring Boot 3.x includes GraalVM support via `spring-boot-starter-aot`. When using with native images:

1. Add Spring Boot AOT plugin to build
2. Add `@RegisterReflectionForBinding` for DTOs:

```java
@RegisterReflectionForBinding({
    StreamConfig.class,
    StreamEvent.Data.class,
    // ... other types
})
@AutoConfiguration
public class DurableStreamsWebFluxAutoConfiguration { ... }
```

---

### 5. Micronaut Integration (FRAMEWORK-DEPENDENT)

Micronaut is designed for AOT compilation and has excellent GraalVM support.

**Affected Files:**

| File | Annotations |
|------|-------------|
| `DurableStreamsMicronautController.java` | `@Controller`, `@Inject` (in docs) |
| `DurableStreamsFactory.java` | `@Factory`, `@Singleton` |

**Required Configuration:**

Micronaut automatically generates GraalVM configuration at compile time when using:
- `micronaut-inject-java` annotation processor
- Micronaut's build plugins

No manual configuration needed if using Micronaut's standard build setup.

---

### 6. Quarkus Integration (FRAMEWORK-DEPENDENT)

Quarkus has first-class GraalVM native image support.

**Affected Files:**

| File | Annotations |
|------|-------------|
| `DurableStreamsQuarkusResource.java` | `@Inject`, JAX-RS annotations |
| `DurableStreamsProducer.java` | `@Inject` (in docs) |

**Required Configuration:**

Quarkus automatically handles native image configuration. Ensure:
- Quarkus native build plugin is configured
- Use `@RegisterForReflection` for any custom types

---

## Code That's GraalVM-Friendly (No Issues)

### Core Module (`durable-streams-core`)

**Zero external dependencies** - This is excellent for native images.

Key well-designed patterns:
- `ControlJson.java`: Hand-written JSON parser (no reflection)
- `SseParser.java`: Manual string parsing
- `Offset.java`: Simple value object
- Pure protocol implementation with no dynamic features

### No Problematic Patterns Found

| Pattern | Status |
|---------|--------|
| `Class.forName()` | Not used |
| `getDeclaredMethod()` / `getMethod()` | Not used |
| `getDeclaredField()` / `getField()` | Not used |
| `Proxy.newProxyInstance()` | Not used |
| `ObjectInputStream` / `ObjectOutputStream` | Not used |
| `MethodHandles` / `VarHandle` | Not used |
| `sun.misc.Unsafe` | Not used |

---

## Potentially Unreachable Code Paths

### 1. ServiceLoader with No Providers

If no `StreamCodecProvider` implementations are available at runtime, the fallback behavior in `ServiceLoaderCodecRegistry` may not be exercised during native image build analysis:

```java
// ServiceLoaderCodecRegistry.java:26-35
ServiceLoader<StreamCodecProvider> loader = ServiceLoader.load(StreamCodecProvider.class, cl);
for (StreamCodecProvider p : loader) {
    // If no providers, this loop body is never executed
}
```

**Impact**: Low - The code handles empty providers gracefully.

### 2. Conditional Bean Creation

Spring Boot's `@ConditionalOnMissingBean` patterns mean some beans may never be created if users provide their own:

```java
@Bean
@ConditionalOnMissingBean
public StreamStore durableStreamsStore() {
    return new InMemoryStreamStore();
}
```

**Impact**: Low - This is expected behavior.

### 3. Expiration Logic

Stream expiration logic in `ReferenceStreamStore` may not be fully exercised:

```java
// ReferenceStreamStore.java:209-210
boolean isExpired(Instant now) {
    return expiresAt != null && !expiresAt.isAfter(now);
}
```

**Impact**: None - Pure logic, no reflection involved.

---

## Recommendations

### Immediate Actions

1. **Add native-image configuration directory**:
   ```
   src/main/resources/META-INF/native-image/io.durablestreams/
   ├── reflect-config.json
   ├── resource-config.json
   └── native-image.properties
   ```

2. **Create ServiceLoader-free alternative**:
   ```java
   // Allow explicit codec registration for native image builds
   public static ServiceLoaderCodecRegistry of(StreamCodecProvider... providers) {
       Map<String, StreamCodec> map = new HashMap<>();
       for (StreamCodecProvider p : providers) {
           for (StreamCodec c : p.codecs()) {
               map.put(normalize(c.contentType()), c);
           }
       }
       return new ServiceLoaderCodecRegistry(map);
   }
   ```

3. **Add GraalVM test workflow**:
   ```yaml
   - name: Build native image
     run: ./gradlew nativeCompile
   - name: Test native image
     run: ./gradlew nativeTest
   ```

### Module-Specific Recommendations

| Module | Recommendation |
|--------|----------------|
| `durable-streams-core` | Ready for native images (no changes needed) |
| `durable-streams-server-core` | Add reflect-config.json for ServiceLoader |
| `durable-streams-json-jackson` | Document required Jackson configuration |
| `durable-streams-spring-*` | Add Spring AOT support |
| `durable-streams-micronaut-*` | Use Micronaut's native plugins |
| `durable-streams-quarkus-*` | Use Quarkus native plugins |

### Long-term Improvements

1. Consider providing a `durable-streams-graalvm` module with pre-built configuration
2. Add `@RegisterReflectionForBinding` to Spring starters
3. Document native image build process in README
4. Add CI/CD pipeline for native image testing

---

## Testing GraalVM Compatibility

### Using GraalVM Native Build Tools

```bash
# Install GraalVM
sdk install java 21.0.1-graal

# Build native image (Gradle)
./gradlew nativeCompile

# Run native tests
./gradlew nativeTest
```

### Using GraalVM Tracing Agent

```bash
# Run with tracing agent to generate config
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
  -jar build/libs/your-app.jar

# Review generated config files
ls src/main/resources/META-INF/native-image/
```

---

## Appendix: File Reference

### ServiceLoader Usage Locations
- `durable-streams-server-core/src/main/java/io/durablestreams/server/core/ServiceLoaderCodecRegistry.java:26`
- `durable-streams-server-spi/src/main/java/io/durablestreams/server/spi/ReferenceStreamStore.java:123`

### Jackson Usage Locations
- `durable-streams-json-jackson/src/main/java/io/durablestreams/json/jackson/JacksonJsonCodec.java`
- `durable-streams-json-jackson/src/main/java/io/durablestreams/json/jackson/JacksonJsonStreamCodec.java`
- `durable-streams-conformance-runner/src/main/java/io/durablestreams/conformance/ClientConformanceAdapter.java`

### Sealed Interface Locations
- `durable-streams-core/src/main/java/io/durablestreams/core/StreamEvent.java:13`
- `durable-streams-server-spi/src/main/java/io/durablestreams/server/spi/RateLimiter.java:30`
- `durable-streams-server-core/src/main/java/io/durablestreams/server/core/ResponseBody.java:8`

### Spring AutoConfiguration Locations
- `durable-streams-spring-webflux-starter/src/main/java/.../DurableStreamsWebFluxAutoConfiguration.java`
- `durable-streams-spring-webmvc-starter/src/main/java/.../DurableStreamsWebMvcAutoConfiguration.java`
- `durable-streams-spring-boot-starter/src/main/java/.../DurableStreamsAutoConfiguration.java`
