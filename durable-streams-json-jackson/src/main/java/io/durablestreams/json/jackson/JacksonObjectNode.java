package io.durablestreams.json.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.durablestreams.json.spi.ArrayNode;
import io.durablestreams.json.spi.JsonNode;
import io.durablestreams.json.spi.ObjectNode;

/**
 * Jackson implementation of ObjectNode.
 */
final class JacksonObjectNode extends JacksonJsonNode implements ObjectNode {
    private final com.fasterxml.jackson.databind.node.ObjectNode objectDelegate;

    JacksonObjectNode(com.fasterxml.jackson.databind.node.ObjectNode delegate, ObjectMapper mapper) {
        super(delegate, mapper);
        this.objectDelegate = delegate;
    }

    @Override
    public ObjectNode put(String fieldName, String value) {
        objectDelegate.put(fieldName, value);
        return this;
    }

    @Override
    public ObjectNode put(String fieldName, long value) {
        objectDelegate.put(fieldName, value);
        return this;
    }

    @Override
    public ObjectNode put(String fieldName, int value) {
        objectDelegate.put(fieldName, value);
        return this;
    }

    @Override
    public ObjectNode put(String fieldName, double value) {
        objectDelegate.put(fieldName, value);
        return this;
    }

    @Override
    public ObjectNode put(String fieldName, boolean value) {
        objectDelegate.put(fieldName, value);
        return this;
    }

    @Override
    public ObjectNode set(String fieldName, JsonNode value) {
        objectDelegate.set(fieldName, JacksonJsonNode.unwrap(value));
        return this;
    }

    @Override
    public ObjectNode remove(String fieldName) {
        objectDelegate.remove(fieldName);
        return this;
    }

    @Override
    public ObjectNode putObject(String fieldName) {
        com.fasterxml.jackson.databind.node.ObjectNode child = objectDelegate.putObject(fieldName);
        return new JacksonObjectNode(child, (ObjectMapper) mapper);
    }

    @Override
    public ArrayNode putArray(String fieldName) {
        com.fasterxml.jackson.databind.node.ArrayNode child = objectDelegate.putArray(fieldName);
        return new JacksonArrayNode(child, (ObjectMapper) mapper);
    }

    @Override
    public ObjectNode removeAll() {
        objectDelegate.removeAll();
        return this;
    }
}
