package io.durablestreams.server.core;

/**
 * Fixed-width base36 encoding for monotonically increasing positions, preserving lexicographic order.
 *
 * <p>Not a protocol requirement; this is an internal helper for the in-memory reference store.
 */
/**
 * Fixed-width base36 encoding for monotonically increasing positions, preserving lexicographic order.
 *
 * <p>Not a protocol requirement; this is an internal helper for the in-memory reference store.
 */
public final class LexiLong implements OffsetGenerator {
    private static final int WIDTH = 13; // 36^13 > 2^63

    @Override
    public String encode(io.durablestreams.core.Offset offset) {
        long value = decodeValue(offset);
        if (value < 0) throw new IllegalArgumentException("value must be >= 0");
        String s = Long.toUnsignedString(value, 36);
        if (s.length() > WIDTH) throw new IllegalArgumentException("value too large");
        return "0".repeat(WIDTH - s.length()) + s;
    }

    @Override
    public io.durablestreams.core.Offset decode(String token) {
        if (token == null || token.isEmpty()) throw new IllegalArgumentException("token");
        try {
            long val = Long.parseUnsignedLong(token, 36);
            return new io.durablestreams.core.Offset(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid offset token: " + token, e);
        }
    }

    @Override
    public io.durablestreams.core.Offset next(io.durablestreams.core.Offset previous, long size) {
        long nextValue = decodeValue(previous) + 1;
        return new io.durablestreams.core.Offset(encode(nextValue));
    }

    private static long decodeValue(io.durablestreams.core.Offset offset) {
        if ("-1".equals(offset.value())) return 0;
        try {
            return Long.parseLong(offset.value(), 36);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid offset token: " + offset.value(), e);
        }
    }
}
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
