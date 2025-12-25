package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.CachePolicy;
import io.durablestreams.server.spi.StreamMetadata;

/**
 * Server-Sent Events (SSE) utilities for protocol compliance.
 *
 * <p>Provides helpers for rendering events and control JSON according to
 * the Durable Streams specification.
 */
public final class SseUtils {
    
    private SseUtils() {}
    
    /**
     * Renders a control event JSON payload with proper quoting.
     *
     * @param streamNextOffset next offset value
     * @param streamCursor optional cursor value (may be null)
     * @return quoted JSON string as required by specification
     */
    public static String renderControlJson(String streamNextOffset, String streamCursor) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"streamNextOffset\":\"");
        quoteJsonString(sb, streamNextOffset);
        if (streamCursor != null) {
            sb.append(",\"streamCursor\":\"");
            quoteJsonString(sb, streamCursor);
        }
        sb.append("\"}");
        return sb.toString();
    }

    /**
     * Quotes a JSON string value for use in control events and ETags.
     * Handles backslashes and quotes properly per JSON specification.
     *
     * @param sb StringBuilder to append to
     * @param value the string value to quote
     */
    private static void quoteJsonString(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\b') {
                sb.append("\\b");
            } else if (c == '\f') {
                sb.append("\\f");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
    }
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * Formats ETag value as quoted string.
     *
     * <p>Spec shows ETags as quoted: "stream:from:to"
     * This helper ensures consistent quoting.
     *
     * @param internalId the internal stream identifier
     * @param fromOffset the starting offset (may be encoded)
     * @param toOffset the ending offset (may be encoded)
     * @return quoted ETag value
     */
    public static String quotedEtag(String internalId, String fromOffset, String toOffset) {
        return "\"" + internalId + ":" + fromOffset + ":" + toOffset + "\"";
    }
}