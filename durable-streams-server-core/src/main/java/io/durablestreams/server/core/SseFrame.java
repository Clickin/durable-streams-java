package io.durablestreams.server.core;

import java.util.Objects;

/**
 * Server-Sent Events (SSE) frame.
 *
 * <p>Durable Streams uses event types: "data" and "control".
 */
public final class SseFrame {
    private final String event;
    private final String data;

    public SseFrame(String event, String data) {
        this.event = Objects.requireNonNull(event, "event");
        this.data = data == null ? "" : data;
    }

    public String event() {
        return event;
    }

    public String data() {
        return data;
    }

    /**
     * Render as an SSE event block (without HTTP headers).
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event).append("\n");
        // data can include newlines; each line must be prefixed with "data:"
        String[] lines = data.split("\r?\n", -1);
        for (String line : lines) {
            sb.append("data: ").append(line).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }
}
