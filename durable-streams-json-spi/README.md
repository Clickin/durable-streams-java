# Durable Streams JSON SPI

This module provides a JSON serialization/deserialization abstraction layer that allows developers to choose their preferred JSON library implementation (Jackson, Gson, Moshi, kotlinx-serialization, micronaut-serialization, etc.).

## Overview

The JSON SPI (Service Provider Interface) defines a set of interfaces that abstract JSON operations:

- **JsonCodec** - Main interface for JSON serialization and deserialization
- **JsonNode** - Abstraction for JSON tree nodes
- **ObjectNode** - Mutable JSON object builder
- **ArrayNode** - Mutable JSON array builder
- **JsonException** - Base exception for JSON operations

## Usage

### Basic Serialization/Deserialization

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

### JSON Tree Navigation

```java
// Parse to tree
JsonNode node = codec.readTree(jsonBytes);

// Navigate the tree
if (node.isObject()) {
    String name = node.get("name").asText();
    int value = node.get("value").asInt();
}

if (node.isArray()) {
    for (JsonNode element : node.elements()) {
        System.out.println(element.asText());
    }
}

// Check fields
if (node.has("optional")) {
    String optional = node.get("optional").asText("default");
}
```

### Building JSON Structures

```java
// Create an object
ObjectNode obj = codec.createObjectNode();
obj.put("name", "John");
obj.put("age", 30);
obj.put("active", true);

// Create nested structures
ObjectNode address = obj.putObject("address");
address.put("city", "New York");
address.put("zip", "10001");

// Create arrays
ArrayNode tags = obj.putArray("tags");
tags.add("java");
tags.add("json");
tags.add("api");

// Serialize the built structure
String json = codec.writeString(obj);
```

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

// Or provide custom codec
JsonStreamHelper helper = new JsonStreamHelper(myCustomCodec);

// Wrap values for appending
byte[] data = helper.wrapForAppend(myObject);

// Serialize batches
byte[] batch = helper.serializeBatch(List.of(obj1, obj2, obj3));

// Parse messages
List<MyObject> messages = helper.parseMessages(data, MyObject.class);
```

### Client Conformance Adapter

The conformance test adapter uses the abstraction:

```java
import io.durablestreams.json.jackson.JacksonJsonCodec;
import io.durablestreams.json.spi.*;

JsonCodec codec = new JacksonJsonCodec();
JsonNode command = codec.readTree(jsonString);
ObjectNode response = codec.createObjectNode();
response.put("status", 200);
```

## Migration Guide

If you have existing code using Jackson's `ObjectMapper` directly:

### Before
```java
ObjectMapper mapper = new ObjectMapper();
byte[] json = mapper.writeValueAsBytes(object);
MyObject obj = mapper.readValue(json, MyObject.class);
JsonNode node = mapper.readTree(json);
ObjectNode objNode = mapper.createObjectNode();
```

### After
```java
JsonCodec codec = new JacksonJsonCodec();
byte[] json = codec.writeBytes(object);
MyObject obj = codec.readValue(json, MyObject.class);
JsonNode node = codec.readTree(json);
ObjectNode objNode = codec.createObjectNode();
```

The API is designed to be similar to Jackson's `ObjectMapper` for easy migration.

## Benefits

1. **Library Independence** - Not locked into Jackson; can switch to Gson, Moshi, or any other JSON library
2. **Framework Integration** - Easily integrate with Micronaut, Quarkus, or Spring's preferred JSON libraries
3. **Consistent API** - Uniform interface regardless of underlying implementation
4. **Type Safety** - Strong typing with generics for serialization/deserialization
5. **Testability** - Mock or stub JSON operations in tests

## Design Principles

- **Minimal Dependencies** - The SPI module has zero dependencies
- **Simple API** - Common operations are straightforward and intuitive
- **Extensible** - Easy to implement for any JSON library
- **Performance** - Thin wrapper with minimal overhead
- **Exception Handling** - Unified exception hierarchy

## License

Same as parent project.
