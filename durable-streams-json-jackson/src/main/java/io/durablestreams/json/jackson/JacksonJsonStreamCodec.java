package io.durablestreams.json.jackson;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.durablestreams.server.spi.StreamCodec;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JSON codec for {@code application/json} using Jackson.
 *
 * <p>Use {@link #INSTANCE} for GraalVM native-image friendly registration:
 * <pre>{@code
 * StreamCodecRegistry registry = StreamCodecRegistry.builder()
 *     .register(JacksonJsonStreamCodec.INSTANCE)
 *     .build();
 * }</pre>
 *
 * <p>Protocol behavior:
 * <ul>
 *   <li>GET returns a JSON array (possibly empty)</li>
 *   <li>POST body:
 *     <ul>
 *       <li>If body is a JSON array, it is flattened exactly 1 level (each element is appended)</li>
 *       <li>If body is a single JSON value, it is appended as one message</li>
 *       <li>Empty array is rejected</li>
 *     </ul>
 *   </li>
 *   <li>PUT may accept empty array to create an empty stream</li>
 * </ul>
 */
public final class JacksonJsonStreamCodec implements StreamCodec {

    /**
     * Singleton instance for explicit registration (GraalVM native-image friendly).
     */
    public static final JacksonJsonStreamCodec INSTANCE = new JacksonJsonStreamCodec();

    private static final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory());

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public State createEmpty() {
        return new StateImpl();
    }

    @Override
    public void applyInitial(State state, InputStream body) throws Exception {
        Objects.requireNonNull(state, "state");
        if (body == null) return;

        // Empty body -> no-op
        byte[] raw = readAllBytes(body);
        if (raw.length == 0) return;

        JsonNode node = MAPPER.readTree(raw);
        if (node == null) return;

        StateImpl s = (StateImpl) state;
        if (node.isArray()) {
            if (node.size() == 0) return; // PUT allows empty array as empty stream
            for (JsonNode el : node) s.messages.add(el);
        } else {
            s.messages.add(node);
        }
    }

    @Override
    public void append(State state, InputStream body) throws Exception {
        Objects.requireNonNull(state, "state");
        if (body == null) throw new IllegalArgumentException("empty body");

        byte[] raw = readAllBytes(body);
        if (raw.length == 0) throw new IllegalArgumentException("empty body");

        // Use streaming parser for top-level discrimination without reading into tree multiple times.
        try (JsonParser p = MAPPER.getFactory().createParser(raw)) {
            JsonToken t = p.nextToken();
            if (t == null) throw new IllegalArgumentException("invalid json");
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json", e);
        }
        if (node == null) throw new IllegalArgumentException("invalid json");

        StateImpl s = (StateImpl) state;
        if (node.isArray()) {
            if (node.size() == 0) throw new IllegalArgumentException("empty JSON array not allowed");
            for (JsonNode el : node) s.messages.add(el);
        } else {
            s.messages.add(node);
        }
    }

    @Override
    public ReadChunk read(State state, long start, int limit) throws Exception {
        Objects.requireNonNull(state, "state");
        StateImpl s = (StateImpl) state;
        int st = (int) Math.min((long) s.messages.size(), Math.max(0L, start));
        int en = (int) Math.min((long) s.messages.size(), (long) st + (long) Math.max(0, limit));
        boolean upToDate = en >= s.messages.size();

        // Serialize sub-list as JSON array
        String json = MAPPER.writeValueAsString(s.messages.subList(st, en));
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return new ReadChunk(body, en, upToDate);
    }

    @Override
    public long size(State state) {
        return ((StateImpl) state).messages.size();
    }

    private static final class StateImpl implements StreamCodec.State {
        private final List<JsonNode> messages = new ArrayList<>();
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
        return out.toByteArray();
    }
}
