package io.durablestreams.json_jackson;

import io.durablestreams.core.ControlJson;
import io.durablestreams.json.jackson.JacksonJsonCodec;
import io.durablestreams.json.spi.JsonCodec;
import io.durablestreams.json.spi.JsonException;

import java.util.List;
import java.util.Objects;

/**
 * Helper for JSON stream operations.
 * Uses the JsonCodec abstraction to support different JSON implementations.
 */
public final class JsonStreamHelper {
    private final JsonCodec codec;

    /**
     * Creates a helper with the default Jackson JSON codec.
     */
    public JsonStreamHelper() {
        this(new JacksonJsonCodec());
    }

    /**
     * Creates a helper with a custom JSON codec.
     * @param codec the JSON codec to use
     */
    public JsonStreamHelper(JsonCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Wraps a single value in a JSON array for appending to a stream.
     * @param value the value to wrap
     * @return JSON bytes
     * @throws JsonException if serialization fails
     */
    public byte[] wrapForAppend(Object value) throws JsonException {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return codec.writeBytes(List.of(value));
    }

    /**
     * Serializes a batch of values to JSON.
     * @param values the values to serialize
     * @return JSON bytes
     * @throws JsonException if serialization fails
     */
    public byte[] serializeBatch(List<?> values) throws JsonException {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        return codec.writeBytes(values);
    }

    /**
     * Parses JSON bytes to a list of typed objects.
     * @param data JSON bytes
     * @param type element type
     * @return list of deserialized objects
     * @throws JsonException if deserialization fails
     */
    public <T> List<T> parseMessages(byte[] data, Class<T> type) throws JsonException {
        Objects.requireNonNull(type, "type");
        if (data == null || data.length == 0) {
            return List.of();
        }
        return codec.readList(data, type);
    }

    /**
     * Parses a control event from SSE JSON.
     * @param json SSE control JSON
     * @return parsed control event
     */
    public ControlEvent parseControl(String json) {
        ControlJson.Control control = ControlJson.parse(json);
        return new ControlEvent(control.streamNextOffset(), control.streamCursor());
    }

    public record ControlEvent(String streamNextOffset, String streamCursor) {
    }
}

