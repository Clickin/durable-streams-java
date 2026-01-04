package io.durablestreams.core;

/**
 * Minimal parser for Durable Streams SSE control event JSON.
 *
 * <p>Expected fields:
 * <ul>
 *   <li>{@code streamNextOffset} (required)</li>
 *   <li>{@code streamCursor} (optional)</li>
 * </ul>
 */
public final class ControlJson {
    private ControlJson() {}

    /**
     * Represents the parsed control structure.
     *
     * @param streamNextOffset the next offset to fetch from (required)
     * @param streamCursor the cursor for the next fetch (optional, may be null/empty)
     */
    /**
     * Represents the parsed control structure.
     *
     * @param streamNextOffset the next offset to fetch from (required)
     * @param streamCursor the cursor for the next fetch (optional, may be null/empty)
     */
    public record Control(String streamNextOffset, String streamCursor) {}

    /**
     * Parses the control event JSON.
     *
     * @param json the raw JSON body from the control event
     * @return the parsed {@link Control} object
     * @throws IllegalArgumentException if the JSON is null or missing required fields
     */
    /**
     * Parses the control event JSON.
     *
     * @param json the raw JSON body from the control event
     * @return the parsed {@link Control} object
     * @throws IllegalArgumentException if the JSON is null or missing required fields
     */
    public static Control parse(String json) {
        if (json == null) throw new IllegalArgumentException("json must not be null");

        String next = extractString(json, "streamNextOffset");
        if (next == null || next.isEmpty()) {
            throw new IllegalArgumentException("missing required field: streamNextOffset");
        }
        String cursor = extractString(json, "streamCursor");
        return new Control(next, cursor);
    }

    /**
     * Checks if the control event indicates the client is up-to-date.
     *
     * @param json the raw JSON body from the control event
     * @return {@code true} if the "upToDate" field is present and true
     */
    /**
     * Checks if the control event indicates the client is up-to-date.
     *
     * @param json the raw JSON body from the control event
     * @return {@code true} if the "upToDate" field is present and true
     */
    public static boolean parseUpToDate(String json) {
        if (json == null) return false;
        Boolean value = extractBoolean(json, "upToDate");
        return Boolean.TRUE.equals(value);
    }

    private static String extractString(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + k.length());
        if (colon < 0) return null;

        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;

        if (p >= json.length() || json.charAt(p) != '"') return null;
        int q1 = p;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;

        return json.substring(q1 + 1, q2);
    }

    private static Boolean extractBoolean(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + k.length());
        if (colon < 0) return null;

        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return null;

        if (json.startsWith("true", p)) return Boolean.TRUE;
        if (json.startsWith("false", p)) return Boolean.FALSE;
        return null;
    }
}
