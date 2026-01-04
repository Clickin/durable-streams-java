package io.github.clickin.json.jackson;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.clickin.json.spi.JsonCodec;
import io.github.clickin.json.spi.JsonException;

import java.io.InputStream;
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
            JavaType listType = mapper.getTypeFactory().constructCollectionType(List.class, elementType);
            return mapper.readValue(data, listType);
        } catch (Exception e) {
            throw new JsonException("Failed to deserialize bytes to List<" + elementType.getName() + ">", e);
        }
    }

    @Override
    public <T> List<T> readList(String json, Class<T> elementType) throws JsonException {
        try {
            JavaType listType = mapper.getTypeFactory().constructCollectionType(List.class, elementType);
            return mapper.readValue(json, listType);
        } catch (Exception e) {
            throw new JsonException("Failed to deserialize string to List<" + elementType.getName() + ">", e);
        }
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
