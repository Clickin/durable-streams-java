package io.durablestreams.core;

import java.util.Objects;

/**
 * Opaque, strictly increasing, lexicographically sortable offset.
 *
 * <p>Offsets are treated as opaque strings by clients. Servers define the encoding but MUST ensure that
 * offsets are lexicographically sortable and strictly increasing with append order.
 *
 * <p>Validation is conservative and primarily prevents query-string conflicts.
 */
public final class Offset implements Comparable<Offset> {

    private final String value;

    public Offset(String value) {
        this.value = validate(value);
    }

    public static Offset beginning() {
        return new Offset(Protocol.OFFSET_BEGINNING);
    }

    public String value() {
        return value;
    }

    private static String validate(String v) {
        Objects.requireNonNull(v, "offset");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("offset must not be empty");
        }
        if (containsAny(v, ",&=?")) {
            throw new IllegalArgumentException("offset contains forbidden characters: , & = ?");
        }
        return v;
    }

    private static boolean containsAny(String s, String chars) {
        for (int i = 0; i < chars.length(); i++) {
            if (s.indexOf(chars.charAt(i)) >= 0) return true;
        }
        return false;
    }

    @Override
    public int compareTo(Offset o) {
        return this.value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Offset)) return false;
        return value.equals(((Offset) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
