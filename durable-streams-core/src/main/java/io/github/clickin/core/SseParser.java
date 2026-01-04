package io.github.clickin.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal SSE parser suitable for Durable Streams "data" and "control" events.
 */
public final class SseParser implements AutoCloseable {

    public record Event(String eventType, String data) {}

    private final BufferedReader in;

    /**
     * Creates a new SSE parser reading from the given input stream.
     *
     * @param is the input stream to read from
     */
    public SseParser(InputStream is) {
        this.in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }

    /**
     * Reads the next SSE event from the stream.
     *
     * @return the next event, or {@code null} if EOF is reached
     * @throws IOException if an I/O error occurs
     */
    public Event next() throws IOException {
        String eventType = "message";
        StringBuilder data = new StringBuilder();
        boolean seenAny = false;

        String line;
        while ((line = in.readLine()) != null) {
            seenAny = true;
            if (line.isEmpty()) break;
            if (line.startsWith("event:")) {
                eventType = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim()).append("\n");
            }
        }

        if (!seenAny) return null;
        return new Event(eventType, stripTrailingNewline(data.toString()));
    }

    private static String stripTrailingNewline(String s) {
        int len = s.length();
        while (len > 0 && (s.charAt(len - 1) == '\n' || s.charAt(len - 1) == '\r')) len--;
        return s.substring(0, len);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
