package io.durablestreams.json.spi;

/**
 * Mutable JSON object node builder.
 * Allows constructing JSON objects programmatically.
 */
public interface ObjectNode extends JsonNode {

    /**
     * Sets a string field.
     */
    ObjectNode put(String fieldName, String value);

    /**
     * Sets a long field.
     */
    ObjectNode put(String fieldName, long value);

    /**
     * Sets an int field.
     */
    ObjectNode put(String fieldName, int value);

    /**
     * Sets a double field.
     */
    ObjectNode put(String fieldName, double value);

    /**
     * Sets a boolean field.
     */
    ObjectNode put(String fieldName, boolean value);

    /**
     * Sets a JsonNode field.
     */
    ObjectNode set(String fieldName, JsonNode value);

    /**
     * Removes a field.
     */
    ObjectNode remove(String fieldName);

    /**
     * Creates a new object node and sets it as a field.
     */
    ObjectNode putObject(String fieldName);

    /**
     * Creates a new array node and sets it as a field.
     */
    ArrayNode putArray(String fieldName);

    /**
     * Removes all fields.
     */
    ObjectNode removeAll();
}
