package io.durablestreams.server.core;

public final class SseUtils {
    private SseUtils() {}

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

    public static String quotedEtag(String internalId, String fromOffset, String toOffset) {
        return "\"" + internalId + ":" + fromOffset + ":" + toOffset + "\"";
    }

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
}
