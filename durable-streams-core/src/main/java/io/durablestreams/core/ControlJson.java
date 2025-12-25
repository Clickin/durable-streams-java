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

    public record Control(String streamNextOffset, String streamCursor) {}

    public static Control parse(String json) {
        if (json == null) throw new IllegalArgumentException("json must not be null");

        String next = extractString(json, "streamNextOffset");
        if (next == null || next.isEmpty()) {
            throw new IllegalArgumentException("missing required field: streamNextOffset");
        }
        String cursor = extractString(json, "streamCursor");
        return new Control(next, cursor);
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
}
