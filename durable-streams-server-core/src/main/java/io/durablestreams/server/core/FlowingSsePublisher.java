package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.CursorPolicy;
import io.durablestreams.server.spi.ReadOutcome;
import io.durablestreams.server.spi.StreamStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE Publisher that streams "data" and "control" events, then completes after a max duration.
 *
 * <p>This is a reference implementation and intentionally simple.
 */
final class FlowingSsePublisher implements Flow.Publisher<SseFrame> {

    private final StreamStore store;
    private final CursorPolicy cursorPolicy;
    private final java.net.URI url;
    private Offset offset;
    private final int maxChunkSize;
    private final Duration maxDuration;
    private final Clock clock;

    FlowingSsePublisher(
            StreamStore store,
            CursorPolicy cursorPolicy,
            java.net.URI url,
            Offset offset,
            int maxChunkSize,
            Duration maxDuration,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.cursorPolicy = Objects.requireNonNull(cursorPolicy, "cursorPolicy");
        this.url = Objects.requireNonNull(url, "url");
        this.offset = Objects.requireNonNull(offset, "offset");
        this.maxChunkSize = maxChunkSize;
        this.maxDuration = maxDuration;
        this.clock = clock;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super SseFrame> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscriber.onSubscribe(new Sub(subscriber));
    }

    private final class Sub implements Flow.Subscription, Runnable {
        private final Flow.Subscriber<? super SseFrame> sub;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile long demand;

        Sub(Flow.Subscriber<? super SseFrame> sub) {
            this.sub = sub;
            new Thread(this, "durable-streams-sse-publisher").start();
        }

        @Override
        public void request(long n) {
            if (n <= 0) return;
            demand = Math.addExact(demand, n);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public void run() {
            Instant started = clock.instant();
            try {
                while (!cancelled.get() && Duration.between(started, clock.instant()).compareTo(maxDuration) < 0) {
                    if (demand <= 0) {
                        Thread.sleep(5);
                        continue;
                    }

                    ReadOutcome out = store.read(url, offset, maxChunkSize);
                    if (out.status() != ReadOutcome.Status.OK) {
                        sub.onError(new IllegalStateException("SSE read failed: " + out.status()));
                        return;
                    }

                    byte[] body = out.body() == null ? new byte[0] : out.body();
                    boolean hasBody = body.length > 0;

                    if (!hasBody && out.upToDate()) {
                        // Wait briefly for new data, then loop (until maxDuration)
                        boolean ready = store.await(url, offset, Duration.ofSeconds(5));
                        if (!ready) continue;
                        continue;
                    }

                    // Emit data event (as UTF-8 text)
                    String data = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                    sub.onNext(new SseFrame("data", data));
                    demand--;

                    // Emit control event after every data event
                    String cursor = cursorPolicy.nextCursor(null);
                    String controlJson = "{\"streamNextOffset\":\"" + out.nextOffset().value() + "\",\"streamCursor\":\"" + cursor + "\"}";
                    sub.onNext(new SseFrame("control", controlJson));
                    demand--;

                    offset = out.nextOffset();
                }
                sub.onComplete();
            } catch (Throwable t) {
                sub.onError(t);
            }
        }
    }
}
