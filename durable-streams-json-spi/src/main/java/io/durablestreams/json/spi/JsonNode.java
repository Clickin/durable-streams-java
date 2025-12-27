package io.durablestreams.json.spi;

import java.util.Iterator;
import java.util.Map;

/**
 * Abstraction for a JSON tree node. Represents any JSON value (object, array, string, number, boolean, null).
 * Implementations should provide access to node content and structure without exposing
 * the underlying JSON library.
 */
public interface JsonNode {

    /**
     * Returns the node type.
     */
    JsonNodeType getNodeType();

    /**
     * Returns true if this is an object node.
     */
    default boolean isObject() {
        return getNodeType() == JsonNodeType.OBJECT;
    }

    /**
     * Returns true if this is an array node.
     */
    default boolean isArray() {
        return getNodeType() == JsonNodeType.ARRAY;
    }

    /**
     * Returns true if this is a text node.
     */
    default boolean isTextual() {
        return getNodeType() == JsonNodeType.STRING;
    }

    /**
     * Returns true if this is a numeric node.
     */
    default boolean isNumber() {
        return getNodeType() == JsonNodeType.NUMBER;
    }

    /**
     * Returns true if this is a boolean node.
     */
    default boolean isBoolean() {
        return getNodeType() == JsonNodeType.BOOLEAN;
    }

    /**
     * Returns true if this is a null node.
     */
    default boolean isNull() {
        return getNodeType() == JsonNodeType.NULL;
    }

    /**
     * Gets a field by name from an object node.
     * Returns null if this is not an object or the field doesn't exist.
     */
    JsonNode get(String fieldName);

    /**
     * Gets an element by index from an array node.
     * Returns null if this is not an array or index is out of bounds.
     */
    JsonNode get(int index);

    /**
     * Returns true if this node has a field with the given name.
     */
    boolean has(String fieldName);

    /**
     * Returns the size of this node.
     * For objects: number of fields
     * For arrays: number of elements
     * For others: 0
     */
    int size();

    /**
     * Returns the text value of this node.
     * For text nodes: the string value
     * For other types: string representation
     */
    String asText();

    /**
     * Returns the text value or the default if this is a null node.
     */
    String asText(String defaultValue);

    /**
     * Returns the long value of this node.
     * For numeric nodes: the long value
     * For text nodes: parsed long
     * For others: 0
     */
    long asLong();

    /**
     * Returns the long value or the default if not numeric.
     */
    long asLong(long defaultValue);

    /**
     * Returns the int value of this node.
     */
    int asInt();

    /**
     * Returns the int value or the default if not numeric.
     */
    int asInt(int defaultValue);

    /**
     * Returns the double value of this node.
     */
    double asDouble();

    /**
     * Returns the double value or the default if not numeric.
     */
    double asDouble(double defaultValue);

    /**
     * Returns the boolean value of this node.
     */
    boolean asBoolean();

    /**
     * Returns the boolean value or the default if not boolean.
     */
    boolean asBoolean(boolean defaultValue);

    /**
     * Returns an iterator over the field names (for object nodes).
     */
    Iterator<String> fieldNames();

    /**
     * Returns an iterator over the field entries (for object nodes).
     */
    Iterator<Map.Entry<String, JsonNode>> fields();

    /**
     * Returns an iterator over the elements (for array nodes).
     */
    Iterator<JsonNode> elements();

    /**
     * Converts this node to a Java object of the specified type.
     * @throws JsonException if conversion fails
     */
    <T> T toObject(Class<T> type) throws JsonException;
}
