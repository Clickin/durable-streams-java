package io.durablestreams.json_jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.durablestreams.core.ControlJson;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class JsonStreamHelper {
    private final ObjectMapper mapper;

    public JsonStreamHelper() {
        this(new ObjectMapper());
    }

    public JsonStreamHelper(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public byte[] wrapForAppend(Object value) throws JsonProcessingException {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return mapper.writeValueAsBytes(List.of(value));
    }

    public byte[] serializeBatch(List<?> values) throws JsonProcessingException {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        return mapper.writeValueAsBytes(values);
    }

    public <T> List<T> parseMessages(byte[] data, Class<T> type) throws IOException {
        Objects.requireNonNull(type, "type");
        if (data == null || data.length == 0) {
            return List.of();
        }
        return mapper.readerForListOf(type).readValue(data);
    }

    public ControlEvent parseControl(String json) {
        ControlJson.Control control = ControlJson.parse(json);
        return new ControlEvent(control.streamNextOffset(), control.streamCursor());
    }

    public record ControlEvent(String streamNextOffset, String streamCursor) {
    }
}
