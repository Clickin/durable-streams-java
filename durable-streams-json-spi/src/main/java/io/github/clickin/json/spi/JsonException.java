package io.github.clickin.json.spi;

/**
 * Base exception for JSON serialization and deserialization errors.
 */
public class JsonException extends Exception {
    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }

    public JsonException(Throwable cause) {
        super(cause);
    }
}
