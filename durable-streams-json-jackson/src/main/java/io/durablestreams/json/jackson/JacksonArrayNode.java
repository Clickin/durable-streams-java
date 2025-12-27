package io.durablestreams.json.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.durablestreams.json.spi.ArrayNode;
import io.durablestreams.json.spi.JsonNode;
import io.durablestreams.json.spi.ObjectNode;

/**
 * Jackson implementation of ArrayNode.
 */
final class JacksonArrayNode extends JacksonJsonNode implements ArrayNode {
    private final com.fasterxml.jackson.databind.node.ArrayNode arrayDelegate;

    JacksonArrayNode(com.fasterxml.jackson.databind.node.ArrayNode delegate, ObjectMapper mapper) {
        super(delegate, mapper);
        this.arrayDelegate = delegate;
    }

    @Override
    public ArrayNode add(String value) {
        arrayDelegate.add(value);
        return this;
    }

    @Override
    public ArrayNode add(long value) {
        arrayDelegate.add(value);
        return this;
    }

    @Override
    public ArrayNode add(int value) {
        arrayDelegate.add(value);
        return this;
    }

    @Override
    public ArrayNode add(double value) {
        arrayDelegate.add(value);
        return this;
    }

    @Override
    public ArrayNode add(boolean value) {
        arrayDelegate.add(value);
        return this;
    }

    @Override
    public ArrayNode add(JsonNode value) {
        arrayDelegate.add(JacksonJsonNode.unwrap(value));
        return this;
    }

    @Override
    public ObjectNode addObject() {
        com.fasterxml.jackson.databind.node.ObjectNode child = arrayDelegate.addObject();
        return new JacksonObjectNode(child, (ObjectMapper) mapper);
    }

    @Override
    public ArrayNode addArray() {
        com.fasterxml.jackson.databind.node.ArrayNode child = arrayDelegate.addArray();
        return new JacksonArrayNode(child, (ObjectMapper) mapper);
    }

    @Override
    public ArrayNode removeAll() {
        arrayDelegate.removeAll();
        return this;
    }
}
