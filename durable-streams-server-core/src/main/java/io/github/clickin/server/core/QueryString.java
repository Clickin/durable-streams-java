package io.github.clickin.server.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class QueryString {
    private QueryString() {}

    public static Map<String, String> parse(URI uri) {
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

    public static boolean hasDuplicate(URI uri, String key) {
        if (uri == null || key == null) return false;
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) return false;
        int count = 0;
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String rawKey = eq < 0 ? part : part.substring(0, eq);
            String decodedKey = decode(rawKey);
            if (key.equals(decodedKey)) {
                count++;
                if (count > 1) return true;
            }
        }
        return false;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
