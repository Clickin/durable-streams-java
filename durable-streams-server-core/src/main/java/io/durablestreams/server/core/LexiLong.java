package io.durablestreams.server.core;

/**
 * Fixed-width base36 encoding for monotonically increasing positions, preserving lexicographic order.
 *
 * <p>Not a protocol requirement; this is an internal helper for the in-memory reference store.
 */
final class LexiLong {
    private static final int WIDTH = 13; // 36^13 > 2^63

    static String encode(long value) {
        if (value < 0) throw new IllegalArgumentException("value must be >= 0");
        String s = Long.toUnsignedString(value, 36);
        if (s.length() > WIDTH) throw new IllegalArgumentException("value too large");
        return "0".repeat(WIDTH - s.length()) + s;
    }

    static long decode(String token) {
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("token");
        return Long.parseUnsignedLong(token, 36);
    }
}
