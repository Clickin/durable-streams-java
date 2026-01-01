package io.durablestreams.server.spi;

import io.durablestreams.core.Offset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceStreamStoreTest {

    private ReferenceStreamStore store;
    private static final URI URL = URI.create("http://localhost/test");
    private static final String TEXT_CT = "text/plain";

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.of("UTC"));
        store = new ReferenceStreamStore(new SimpleOffsetGenerator(), StreamCodecRegistry.bytesOnly(), clock);
    }

    @Test
    void testCreate() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        CreateOutcome outcome = store.create(URL, config, new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
        
        assertEquals(CreateOutcome.Status.CREATED, outcome.status());
        assertEquals("5", outcome.nextOffset().value());
    }

    @Test
    void testAppend() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        
        AppendOutcome outcome = store.append(URL, TEXT_CT, null, new ByteArrayInputStream("World".getBytes(StandardCharsets.UTF_8)));
        assertEquals(AppendOutcome.Status.APPENDED, outcome.status());
        assertEquals("5", outcome.nextOffset().value());
    }

    @Test
    void testRead() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
        
        ReadOutcome outcome = store.read(URL, new Offset("0"), 100);
        assertEquals(ReadOutcome.Status.OK, outcome.status());
        assertEquals("Hello", new String(outcome.body(), StandardCharsets.UTF_8));
        assertEquals("5", outcome.nextOffset().value());
    }

    @Test
    void testDelete() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        assertTrue(store.delete(URL));
        assertFalse(store.delete(URL));
    }

    @Test
    void testHead() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        assertTrue(store.head(URL).isPresent());
        store.delete(URL);
        assertFalse(store.head(URL).isPresent());
    }

    @Test
    void testAwait() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        
        assertFalse(store.await(URL, new Offset("0"), Duration.ofMillis(10)));
        
        store.append(URL, TEXT_CT, null, new ByteArrayInputStream("A".getBytes(StandardCharsets.UTF_8)));
        assertTrue(store.await(URL, new Offset("0"), Duration.ofMillis(10)));
    }

    static class SimpleOffsetGenerator implements OffsetGenerator {
        @Override
        public Offset next(Offset previous, long size) {
            if (size < 0) throw new IllegalArgumentException("size must be >= 0");
            return new Offset(Long.toString(size, 36));
        }

        @Override
        public String encode(Offset offset) {
            return offset.value();
        }

        @Override
        public Offset decode(String token) throws IllegalArgumentException {
            return new Offset(token);
        }
    }
}
