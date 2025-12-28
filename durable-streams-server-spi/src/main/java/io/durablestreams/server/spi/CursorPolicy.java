package io.durablestreams.server.spi;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Server-generated cursor policy per protocol "Caching and Collapsing" section.
 *
 * <p>Cursors are interval numbers (decimal strings) counted from a fixed epoch. When the client
 * echoes a cursor value that is >= the current interval number, the server must return a cursor
 * strictly greater than the client's cursor by applying random jitter.
 */
public final class CursorPolicy {

    public static final Instant DEFAULT_EPOCH = Instant.parse("2024-10-09T00:00:00Z");
    public static final int DEFAULT_INTERVAL_SECONDS = 20;

    private final Clock clock;
    private final Instant epoch;
    private final int intervalSeconds;
    private final SecureRandom random = new SecureRandom();

    private long lastIssued = -1;

    public CursorPolicy(Clock clock) {
        this(clock, DEFAULT_EPOCH, DEFAULT_INTERVAL_SECONDS);
    }

    public CursorPolicy(Clock clock, Instant epoch, int intervalSeconds) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        if (intervalSeconds <= 0) throw new IllegalArgumentException("intervalSeconds must be > 0");
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * Generate the next cursor value for a live response.
     *
     * @param clientCursorOrNull cursor echoed by client (long-poll only), or null
     * @return cursor string to return to client
     */
    public synchronized String nextCursor(String clientCursorOrNull) {
        long nowInterval = intervalOf(clock.instant());
        long cursor = Math.max(nowInterval, lastIssued);

        if (clientCursorOrNull != null) {
            long client = parseLongSafe(clientCursorOrNull);
            if (client >= cursor) {
                // Add random jitter of 1..3600 seconds, expressed in interval units.
                int jitterSeconds = 1 + random.nextInt(3600);
                long jitterIntervals = Math.max(1, jitterSeconds / intervalSeconds);
                cursor = client + jitterIntervals;
            }
        }

        // MUST not go backwards.
        if (cursor < lastIssued) cursor = lastIssued;

        lastIssued = cursor;
        return Long.toString(cursor);
    }

    private long intervalOf(Instant now) {
        long seconds = now.getEpochSecond() - epoch.getEpochSecond();
        if (seconds < 0) seconds = 0;
        return seconds / intervalSeconds;
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return -1L; }
    }
}
