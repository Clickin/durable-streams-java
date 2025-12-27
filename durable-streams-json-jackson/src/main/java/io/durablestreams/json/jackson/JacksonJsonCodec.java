package io.durablestreams.json.jackson;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.durablestreams.json.spi.ArrayNode;
import io.durablestreams.json.spi.JsonCodec;
import io.durablestreams.json.spi.JsonException;
import io.durablestreams.json.spi.JsonNode;
import io.durablestreams.json.spi.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Jackson implementation of JsonCodec.
 * Provides JSON serialization/deserialization using Jackson.
 */
public final class JacksonJsonCodec implements JsonCodec {
    private final ObjectMapper mapper;

    /**
     * Creates a Jackson codec with the default ObjectMapper.
     */
    public JacksonJsonCodec() {
        this(new ObjectMapper(new JsonFactory()));
    }

    /**
     * Creates a Jackson codec with a custom ObjectMapper.
     * @param mapper the ObjectMapper to use
     */
    public JacksonJsonCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Returns the underlying ObjectMapper for advanced usage.
     */
    public ObjectMapper getMapper() {
        return mapper;
    }

    @Override
    public byte[] writeBytes(Object value) throws JsonException {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new JsonException("Failed to serialize object to bytes", e);
        }
    }

    @Override
    public String writeString(Object value) throws JsonException {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new JsonException("Failed to serialize object to string", e);
        }
    }

    @Override
    public <T> T readValue(byte[] data, Class<T> type) throws JsonException {
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new JsonException("Failed to deserialize bytes to " + type.getName(), e);
        }
    }

    @Override
    public <T> T readValue(String json, Class<T> type) throws JsonException {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new JsonException("Failed to deserialize string to " + type.getName(), e);
        }
    }

    @Override
    public <T> T readValue(InputStream input, Class<T> type) throws JsonException {
        try {
            return mapper.readValue(input, type);
        } catch (Exception e) {
            throw new JsonException("Failed to deserialize input stream to " + type.getName(), e);
        }
    }

    @Override
    public <T> List<T> readList(byte[] data, Class<T> elementType) throws JsonException {
        try {
            return mapper.readValue(data, new TypeReference<List<T>>() {});
        } catch (Exception e) {
            throw new JsonException("Failed to deserialize bytes to List<" + elementType.getName() + ">", e);
        }
    }

    @Override
    public <T> List<T> readList(String json, Class<T> elementType) throws JsonException {
        try {
            return mapper.readValue(json, new TypeReference<List<T>>() {});
        } catch (Exception e) {
            throw new JsonException("Failed to deserialize string to List<" + elementType.getName() + ">", e);
        }
    }

    @Override
    public JsonNode readTree(byte[] data) throws JsonException {
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(data);
            return JacksonJsonNode.wrap(node, mapper);
        } catch (Exception e) {
            throw new JsonException("Failed to parse bytes to tree", e);
        }
    }

    @Override
    public JsonNode readTree(String json) throws JsonException {
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
            return JacksonJsonNode.wrap(node, mapper);
        } catch (Exception e) {
            throw new JsonException("Failed to parse string to tree", e);
        }
    }

    @Override
    public JsonNode readTree(InputStream input) throws JsonException {
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(input);
            return JacksonJsonNode.wrap(node, mapper);
        } catch (Exception e) {
            throw new JsonException("Failed to parse input stream to tree", e);
        }
    }

    @Override
    public ObjectNode createObjectNode() {
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        return new JacksonObjectNode(node, mapper);
    }

    @Override
    public ArrayNode createArrayNode() {
        com.fasterxml.jackson.databind.node.ArrayNode node = mapper.createArrayNode();
        return new JacksonArrayNode(node, mapper);
    }

    @Override
    public boolean isJsonArray(byte[] data) throws JsonException {
        if (data == null || data.length == 0) {
            throw new JsonException("Cannot determine JSON type of empty data");
        }

        try (JsonParser parser = mapper.getFactory().createParser(data)) {
            JsonToken token = parser.nextToken();
            if (token == null) {
                throw new JsonException("Invalid JSON: no tokens");
            }
            return token == JsonToken.START_ARRAY;
        } catch (Exception e) {
            throw new JsonException("Failed to validate JSON", e);
        }
    }
}
