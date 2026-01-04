package io.durablestreams.core;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal helpers for case-insensitive protocol header lookup.
 */
public final class Headers {
    private Headers() {}

    /**
     * Finds the first value of a header in a case-insensitive manner.
     *
     * @param headers the map of headers (keys are header names, values are lists of strings)
     * @param name the header name to search for (case-insensitive)
     * @return an Optional containing the first value found, or empty if not present
     */
    public static Optional<String> firstValue(Map<String, ? extends Iterable<String>> headers, String name) {
        if (headers == null || name == null) return Optional.empty();
        String target = name.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, ? extends Iterable<String>> e : headers.entrySet()) {
            if (e.getKey() == null) continue;
            if (e.getKey().toLowerCase(Locale.ROOT).equals(target)) {
                Iterable<String> vals = e.getValue();
                if (vals == null) return Optional.empty();
                for (String v : vals) {
                    if (v != null) return Optional.of(v);
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Checks if a header value represents a boolean "true".
     *
     * @param v the optional header value string
     * @return true if the value is present and equals "true" (case-insensitive), false otherwise
     */
    public static boolean isTrue(Optional<String> v) {
        return v.isPresent() && Protocol.BOOL_TRUE.equalsIgnoreCase(v.get().trim());
    }
}
