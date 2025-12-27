# Durable Streams JSON SPI

This module provides a minimal JSON serialization/deserialization abstraction that allows developers to choose their preferred JSON library implementation (Jackson, Gson, Moshi, kotlinx-serialization, micronaut-serialization, etc.).

## Philosophy

This abstraction is intentionally **minimal** - it only provides serialization and deserialization of typed objects. It does NOT provide a tree model abstraction (JsonNode, ObjectNode, etc.).

**Why?** Tree models tie you to specific library APIs and add complexity. Instead, use **strongly-typed POJOs** for your domain objects.

## Overview

The JSON SPI defines:

- **JsonCodec** - Interface for JSON serialization and deserialization
- **JsonException** - Base exception for JSON operations

That's it! No tree models, no abstractions over library-specific features.

## Usage

### Serialization/Deserialization

```java
import io.durablestreams.json.spi.JsonCodec;
import io.durablestreams.json.jackson.JacksonJsonCodec;

// Create a codec instance (using Jackson implementation)
JsonCodec codec = new JacksonJsonCodec();

// Serialize objects
MyObject obj = new MyObject("test", 123);
byte[] jsonBytes = codec.writeBytes(obj);
String jsonString = codec.writeString(obj);

// Deserialize objects
MyObject deserialized = codec.readValue(jsonBytes, MyObject.class);

// Work with lists
List<MyObject> list = List.of(obj1, obj2, obj3);
byte[] listBytes = codec.writeBytes(list);
List<MyObject> deserializedList = codec.readList(listBytes, MyObject.class);
```

### Use POJOs, Not Tree Models

Instead of building JSON dynamically with tree models, define POJOs:

```java
// Define your domain objects
public record Person(String name, int age, boolean active, Address address, List<String> tags) {}
public record Address(String city, String zip) {}

// Serialize
Person person = new Person("John", 30, true,
    new Address("New York", "10001"),
    List.of("java", "json", "api"));
String json = codec.writeString(person);

// Deserialize
Person parsed = codec.readValue(json, Person.class);
```

This is **cleaner, type-safe, and library-independent**.

## Available Implementations

### Jackson (included)

The `durable-streams-json-jackson` module provides a Jackson implementation:

```java
import io.durablestreams.json.jackson.JacksonJsonCodec;

JsonCodec codec = new JacksonJsonCodec();

// Or with a custom ObjectMapper
ObjectMapper mapper = new ObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
JsonCodec codec = new JacksonJsonCodec(mapper);
```

### Other Implementations

You can implement your own JSON codec for any JSON library:

```java
public class GsonJsonCodec implements JsonCodec {
    private final Gson gson;

    public GsonJsonCodec(Gson gson) {
        this.gson = gson;
    }

    @Override
    public byte[] writeBytes(Object value) throws JsonException {
        try {
            return gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new JsonException("Serialization failed", e);
        }
    }

    // Implement other methods...
}
```

## Integration with Durable Streams

The JSON SPI is used throughout Durable Streams components:

### JsonStreamHelper

```java
import io.durablestreams.json_jackson.JsonStreamHelper;

// Uses default Jackson codec
JsonStreamHelper helper = new JsonStreamHelper();

// Or provide custom codec (Gson, Moshi, etc.)
JsonStreamHelper helper = new JsonStreamHelper(myCustomCodec);

// Wrap values for appending
byte[] data = helper.wrapForAppend(myObject);

// Serialize batches
byte[] batch = helper.serializeBatch(List.of(obj1, obj2, obj3));

// Parse messages
List<MyObject> messages = helper.parseMessages(data, MyObject.class);
```

## Migration Guide

If you have existing code using Jackson's `ObjectMapper` directly:

### Before
```java
ObjectMapper mapper = new ObjectMapper();
byte[] json = mapper.writeValueAsBytes(object);
MyObject obj = mapper.readValue(json, MyObject.class);
List<MyObject> list = mapper.readValue(jsonArray, new TypeReference<List<MyObject>>() {});
```

### After
```java
JsonCodec codec = new JacksonJsonCodec();
byte[] json = codec.writeBytes(object);
MyObject obj = codec.readValue(json, MyObject.class);
List<MyObject> list = codec.readList(jsonArray, MyObject.class);
```

The API is designed to be similar to Jackson's `ObjectMapper` for easy migration.

## Benefits

1. **Library Independence** - Not locked into Jackson; can switch to Gson, Moshi, or any other JSON library
2. **Framework Integration** - Easily integrate with Micronaut, Quarkus, or Spring's preferred JSON libraries
3. **Consistent API** - Uniform interface regardless of underlying implementation
4. **Type Safety** - Strong typing with generics for serialization/deserialization
5. **Testability** - Mock or stub JSON operations in tests

## Design Principles

- **Minimal Surface Area** - Only serialization/deserialization, no tree models or abstractions
- **Zero Dependencies** - The SPI module has zero dependencies
- **POJO-First** - Encourages strongly-typed domain objects over dynamic JSON
- **Easy to Implement** - Any JSON library can implement this in ~100 lines of code
- **Thin Wrapper** - Minimal overhead, direct delegation to underlying library

## License

Same as parent project.
