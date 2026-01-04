package io.github.clickin.core;

/**
 * Base class for Durable Streams related exceptions.
 *
 * <p>Provides a common hierarchy for protocol-level and runtime errors.
 * Subclasses should be specific to the error condition while preserving
 * the original cause when applicable.
 */
public abstract class DurableStreamsException extends RuntimeException {

    protected DurableStreamsException(String message) {
        super(message);
    }

    protected DurableStreamsException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Raised when a provided offset is invalid (malformed, contains forbidden characters).
     */
    public static class InvalidOffset extends DurableStreamsException {
        public InvalidOffset(String message) {
            super(message);
        }
    }

    /**
     * Raised when content-type validation fails or content cannot be processed.
     */
    public static class UnsupportedContentType extends DurableStreamsException {
        public UnsupportedContentType(String message) {
            super(message);
        }
    }

    /**
     * Raised when stream sequence numbers are not strictly increasing.
     */
    public static class SequenceRegression extends DurableStreamsException {
        public SequenceRegression(String message) {
            super(message);
        }
    }

    /**
     * Raised when a cursor value is invalid or cannot be parsed.
     */
    public static class InvalidCursor extends DurableStreamsException {
        public InvalidCursor(String message) {
            super(message);
        }
    }
}