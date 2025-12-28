package io.durablestreams.server.core;

import io.durablestreams.server.spi.AppendOutcome;
import io.durablestreams.server.spi.CreateOutcome;
import io.durablestreams.server.spi.StreamConfig;
import io.durablestreams.server.spi.StreamMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryStreamStoreTest {

    @Test
    void appendAcceptsContentTypeWithCharsetAndCase() throws Exception {
        InMemoryStreamStore store = new InMemoryStreamStore();
        URI stream = URI.create("http://localhost/streams/ct");

        CreateOutcome created = store.create(stream, new StreamConfig("Text/Plain; charset=UTF-8", null, null), null);
        assertThat(created.status()).isEqualTo(CreateOutcome.Status.CREATED);

        AppendOutcome ok = store.append(stream, "text/plain", null, new ByteArrayInputStream("a".getBytes()));
        assertThat(ok.status()).isEqualTo(AppendOutcome.Status.APPENDED);

        AppendOutcome conflict = store.append(stream, "application/octet-stream", null, new ByteArrayInputStream("b".getBytes()));
        assertThat(conflict.status()).isEqualTo(AppendOutcome.Status.CONFLICT);
    }

    @Test
    void expiresStreamByTtlAndAllowsRecreate() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
        InMemoryStreamStore store = new InMemoryStreamStore(new LexiLongOffsetGenerator(), ServiceLoaderCodecRegistry.defaultRegistry(), clock);
        URI stream = URI.create("http://localhost/streams/ttl");

        CreateOutcome created = store.create(stream, new StreamConfig("text/plain", 1L, null), null);
        assertThat(created.status()).isEqualTo(CreateOutcome.Status.CREATED);

        Optional<StreamMetadata> head = store.head(stream);
        assertThat(head).isPresent();

        clock.advance(Duration.ofSeconds(2));

        assertThat(store.head(stream)).isEmpty();

        CreateOutcome recreated = store.create(stream, new StreamConfig("text/plain", 1L, null), null);
        assertThat(recreated.status()).isEqualTo(CreateOutcome.Status.CREATED);
    }

    @Test
    void appendRejectsNonIncreasingSeq() throws Exception {
        InMemoryStreamStore store = new InMemoryStreamStore();
        URI stream = URI.create("http://localhost/streams/seq");

        store.create(stream, new StreamConfig("text/plain", null, null), null);

        AppendOutcome first = store.append(stream, "text/plain", "001", new ByteArrayInputStream("a".getBytes()));
        assertThat(first.status()).isEqualTo(AppendOutcome.Status.APPENDED);

        AppendOutcome conflict = store.append(stream, "text/plain", "000", new ByteArrayInputStream("b".getBytes()));
        assertThat(conflict.status()).isEqualTo(AppendOutcome.Status.CONFLICT);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(instant, ZoneId.of("UTC"));
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
