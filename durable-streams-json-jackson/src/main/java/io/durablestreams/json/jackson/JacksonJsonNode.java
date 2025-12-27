package io.durablestreams.json.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.durablestreams.json.spi.ArrayNode;
import io.durablestreams.json.spi.JsonException;
import io.durablestreams.json.spi.JsonNode;
import io.durablestreams.json.spi.JsonNodeType;
import io.durablestreams.json.spi.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Jackson implementation of JsonNode.
 * Wraps a Jackson JsonNode and delegates all operations to it.
 */
final class JacksonJsonNode implements JsonNode {
    final com.fasterxml.jackson.databind.JsonNode delegate;
    private final ObjectMapper mapper;

    JacksonJsonNode(com.fasterxml.jackson.databind.JsonNode delegate, ObjectMapper mapper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public JsonNodeType getNodeType() {
        if (delegate.isObject()) return JsonNodeType.OBJECT;
        if (delegate.isArray()) return JsonNodeType.ARRAY;
        if (delegate.isTextual()) return JsonNodeType.STRING;
        if (delegate.isNumber()) return JsonNodeType.NUMBER;
        if (delegate.isBoolean()) return JsonNodeType.BOOLEAN;
        return JsonNodeType.NULL;
    }

    @Override
    public JsonNode get(String fieldName) {
        com.fasterxml.jackson.databind.JsonNode child = delegate.get(fieldName);
        return child == null ? null : new JacksonJsonNode(child, mapper);
    }

    @Override
    public JsonNode get(int index) {
        com.fasterxml.jackson.databind.JsonNode child = delegate.get(index);
        return child == null ? null : new JacksonJsonNode(child, mapper);
    }

    @Override
    public boolean has(String fieldName) {
        return delegate.has(fieldName);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public String asText() {
        return delegate.asText();
    }

    @Override
    public String asText(String defaultValue) {
        return delegate.asText(defaultValue);
    }

    @Override
    public long asLong() {
        return delegate.asLong();
    }

    @Override
    public long asLong(long defaultValue) {
        return delegate.asLong(defaultValue);
    }

    @Override
    public int asInt() {
        return delegate.asInt();
    }

    @Override
    public int asInt(int defaultValue) {
        return delegate.asInt(defaultValue);
    }

    @Override
    public double asDouble() {
        return delegate.asDouble();
    }

    @Override
    public double asDouble(double defaultValue) {
        return delegate.asDouble(defaultValue);
    }

    @Override
    public boolean asBoolean() {
        return delegate.asBoolean();
    }

    @Override
    public boolean asBoolean(boolean defaultValue) {
        return delegate.asBoolean(defaultValue);
    }

    @Override
    public Iterator<String> fieldNames() {
        return delegate.fieldNames();
    }

    @Override
    public Iterator<Map.Entry<String, JsonNode>> fields() {
        Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> iter = delegate.fields();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public Map.Entry<String, JsonNode> next() {
                Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = iter.next();
                return Map.entry(entry.getKey(), new JacksonJsonNode(entry.getValue(), mapper));
            }
        };
    }

    @Override
    public Iterator<JsonNode> elements() {
        Iterator<com.fasterxml.jackson.databind.JsonNode> iter = delegate.elements();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public JsonNode next() {
                return new JacksonJsonNode(iter.next(), mapper);
            }
        };
    }

    @Override
    public <T> T toObject(Class<T> type) throws JsonException {
        try {
            return mapper.treeToValue(delegate, type);
        } catch (Exception e) {
            throw new JsonException("Failed to convert node to " + type.getName(), e);
        }
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof JacksonJsonNode other)) return false;
        return delegate.equals(other.delegate);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    // Factory methods for creating wrapped nodes
    static JsonNode wrap(com.fasterxml.jackson.databind.JsonNode node, ObjectMapper mapper) {
        if (node == null) return null;
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode on) {
            return new JacksonObjectNode(on, mapper);
        }
        if (node instanceof com.fasterxml.jackson.databind.node.ArrayNode an) {
            return new JacksonArrayNode(an, mapper);
        }
        return new JacksonJsonNode(node, mapper);
    }

    static ObjectNode wrapObject(com.fasterxml.jackson.databind.node.ObjectNode node, ObjectMapper mapper) {
        return new JacksonObjectNode(node, mapper);
    }

    static ArrayNode wrapArray(com.fasterxml.jackson.databind.node.ArrayNode node, ObjectMapper mapper) {
        return new JacksonArrayNode(node, mapper);
    }

    static com.fasterxml.jackson.databind.JsonNode unwrap(JsonNode node) {
        if (node instanceof JacksonJsonNode jjn) {
            return jjn.delegate;
        }
        throw new IllegalArgumentException("Cannot unwrap non-Jackson JsonNode");
    }
}
