package io.durablestreams.json.spi;

/**
 * Mutable JSON array node builder.
 * Allows constructing JSON arrays programmatically.
 */
public interface ArrayNode extends JsonNode {

    /**
     * Adds a string value.
     */
    ArrayNode add(String value);

    /**
     * Adds a long value.
     */
    ArrayNode add(long value);

    /**
     * Adds an int value.
     */
    ArrayNode add(int value);

    /**
     * Adds a double value.
     */
    ArrayNode add(double value);

    /**
     * Adds a boolean value.
     */
    ArrayNode add(boolean value);

    /**
     * Adds a JsonNode value.
     */
    ArrayNode add(JsonNode value);

    /**
     * Creates a new object node and adds it to this array.
     */
    ObjectNode addObject();

    /**
     * Creates a new array node and adds it to this array.
     */
    ArrayNode addArray();

    /**
     * Removes all elements.
     */
    ArrayNode removeAll();
}
