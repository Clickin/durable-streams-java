package io.durablestreams.json.spi;

import java.io.InputStream;
import java.util.List;

/**
 * Minimal JSON codec interface providing serialization and deserialization.
 * Implementations wrap specific JSON libraries (Jackson, Gson, Moshi, etc.).
 *
 * <p>This interface intentionally avoids exposing tree model abstractions.
 * Instead, use strongly-typed POJOs for your domain objects.
 */
public interface JsonCodec {

    // ===== Serialization =====

    /**
     * Serializes an object to a JSON byte array.
     * @param value the object to serialize
     * @return JSON bytes
     * @throws JsonException if serialization fails
     */
    byte[] writeBytes(Object value) throws JsonException;

    /**
     * Serializes an object to a JSON string.
     * @param value the object to serialize
     * @return JSON string
     * @throws JsonException if serialization fails
     */
    String writeString(Object value) throws JsonException;

    // ===== Deserialization =====

    /**
     * Deserializes JSON bytes to a typed object.
     * @param data JSON bytes
     * @param type target class
     * @return deserialized object
     * @throws JsonException if deserialization fails
     */
    <T> T readValue(byte[] data, Class<T> type) throws JsonException;

    /**
     * Deserializes JSON string to an object of the specified type.
     * @param json JSON string
     * @param type target class
     * @return deserialized object
     * @throws JsonException if deserialization fails
     */
    <T> T readValue(String json, Class<T> type) throws JsonException;

    /**
     * Deserializes JSON input stream to an object of the specified type.
     * @param input JSON input stream
     * @param type target class
     * @return deserialized object
     * @throws JsonException if deserialization fails
     */
    <T> T readValue(InputStream input, Class<T> type) throws JsonException;

    /**
     * Deserializes a JSON array to a list of typed objects.
     * @param data JSON bytes (must be a JSON array)
     * @param elementType element class
     * @return list of deserialized objects
     * @throws JsonException if deserialization fails
     */
    <T> List<T> readList(byte[] data, Class<T> elementType) throws JsonException;

    /**
     * Deserializes a JSON array to a list of typed objects.
     * @param json JSON string (must be a JSON array)
     * @param elementType element class
     * @return list of deserialized objects
     * @throws JsonException if deserialization fails
     */
    <T> List<T> readList(String json, Class<T> elementType) throws JsonException;

    // ===== Validation =====

    /**
     * Checks if the given bytes contain a JSON array.
     * @param data JSON bytes
     * @return true if the JSON is an array, false otherwise
     * @throws JsonException if the data is not valid JSON
     */
    boolean isJsonArray(byte[] data) throws JsonException;
}
