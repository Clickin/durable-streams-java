package io.durablestreams.json.spi;

import java.io.InputStream;
import java.util.List;

/**
 * Main JSON codec interface providing serialization, deserialization, and tree operations.
 * Implementations of this interface wrap specific JSON libraries (Jackson, Gson, Moshi, etc.).
 */
public interface JsonCodec {

    // ===== Serialization methods =====

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

    // ===== Deserialization methods =====

    /**
     * Deserializes JSON bytes to an object of the specified type.
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
     * Deserializes JSON bytes to a list of objects of the specified element type.
     * @param data JSON bytes (must be a JSON array)
     * @param elementType element class
     * @return list of deserialized objects
     * @throws JsonException if deserialization fails
     */
    <T> List<T> readList(byte[] data, Class<T> elementType) throws JsonException;

    /**
     * Deserializes JSON string to a list of objects of the specified element type.
     * @param json JSON string (must be a JSON array)
     * @param elementType element class
     * @return list of deserialized objects
     * @throws JsonException if deserialization fails
     */
    <T> List<T> readList(String json, Class<T> elementType) throws JsonException;

    // ===== Tree model methods =====

    /**
     * Parses JSON bytes to a tree node.
     * @param data JSON bytes
     * @return tree node representation
     * @throws JsonException if parsing fails
     */
    JsonNode readTree(byte[] data) throws JsonException;

    /**
     * Parses JSON string to a tree node.
     * @param json JSON string
     * @return tree node representation
     * @throws JsonException if parsing fails
     */
    JsonNode readTree(String json) throws JsonException;

    /**
     * Parses JSON input stream to a tree node.
     * @param input JSON input stream
     * @return tree node representation
     * @throws JsonException if parsing fails
     */
    JsonNode readTree(InputStream input) throws JsonException;

    /**
     * Creates a new empty object node.
     * @return mutable object node
     */
    ObjectNode createObjectNode();

    /**
     * Creates a new empty array node.
     * @return mutable array node
     */
    ArrayNode createArrayNode();

    // ===== Validation methods =====

    /**
     * Validates that the given bytes contain valid JSON and returns whether it's an array.
     * @param data JSON bytes
     * @return true if the JSON is an array, false if it's an object or primitive
     * @throws JsonException if the data is not valid JSON
     */
    boolean isJsonArray(byte[] data) throws JsonException;
}
