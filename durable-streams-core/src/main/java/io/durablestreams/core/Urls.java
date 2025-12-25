package io.durablestreams.core;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Utility to build URLs with lexicographically sorted query parameter keys.
 */
public final class Urls {
    private Urls() {}

    public static URI withQuery(URI base, Map<String, String> params) {
        Objects.requireNonNull(base, "base");
        if (params == null || params.isEmpty()) return base;

        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder(base.toString());
        sb.append(base.getQuery() == null ? "?" : "&");

        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (!first) sb.append("&");
            first = false;
            sb.append(encode(e.getKey())).append("=").append(encode(e.getValue()));
        }
        return URI.create(sb.toString());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
