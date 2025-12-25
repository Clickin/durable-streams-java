package io.durablestreams.server.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Minimal query string parser (framework-neutral).
 */
final class QueryString {

    private QueryString() {}

    static Map<String, String> parse(URI uri) {
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq < 0) {
                out.put(decode(part), "");
            } else {
                String k = decode(part.substring(0, eq));
                String v = decode(part.substring(eq + 1));
                out.put(k, v);
            }
        }
        return out;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
