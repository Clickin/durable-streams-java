package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.AppendOutcome;
import io.durablestreams.server.spi.CreateOutcome;
import io.durablestreams.server.spi.ReadOutcome;
import io.durablestreams.server.spi.StreamConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class BlockingFileStreamStoreTest {

    @TempDir
    Path tempDir;

    private BlockingFileStreamStore store;
    private static final String JSON_CT = "application/json";
    private static final String TEXT_CT = "text/plain";
    private static final URI URL = URI.create("http://localhost/test");

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.of("UTC"));
        store = new BlockingFileStreamStore(tempDir, clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        store.close();
    }

    @Test
    void testCreateStream() throws Exception {
        StreamConfig config = new StreamConfig(JSON_CT, null, null);
        CreateOutcome outcome = store.create(URL, config, new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)));
        
        assertEquals(CreateOutcome.Status.CREATED, outcome.status());
        assertEquals(LexiLong.encode(0), outcome.nextOffset().value());
    }

    @Test
    void testCreateExistingStreamMatch() throws Exception {
        StreamConfig config = new StreamConfig(JSON_CT, null, null);
        store.create(URL, config, null);
        CreateOutcome outcome = store.create(URL, config, null);
        assertEquals(CreateOutcome.Status.EXISTS_MATCH, outcome.status());
    }

    @Test
    void testCreateExistingStreamConflict() throws Exception {
        StreamConfig config1 = new StreamConfig(JSON_CT, null, null);
        StreamConfig config2 = new StreamConfig(TEXT_CT, null, null);
        
        store.create(URL, config1, null);
        CreateOutcome outcome = store.create(URL, config2, null);
        assertEquals(CreateOutcome.Status.EXISTS_CONFLICT, outcome.status());
    }

    @Test
    void testCreateExpiredStream() throws Exception {
        StreamConfig config = new StreamConfig(JSON_CT, 1L, null);
        store.create(URL, config, null);
        
        Clock futureClock = Clock.fixed(Instant.parse("2024-01-01T00:00:02Z"), ZoneId.of("UTC"));
        BlockingFileStreamStore futureStore = new BlockingFileStreamStore(tempDir, futureClock);
        
        CreateOutcome outcome = futureStore.create(URL, config, null);
        assertEquals(CreateOutcome.Status.CREATED, outcome.status());
        futureStore.close();
    }

    @Test
    void testAppendStream() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);

        AppendOutcome outcome = store.append(URL, TEXT_CT, null, new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(AppendOutcome.Status.APPENDED, outcome.status());
        assertEquals(LexiLong.encode(5), outcome.nextOffset().value());
    }

    @Test
    void testAppendStreamNotFound() throws Exception {
        AppendOutcome outcome = store.append(URL, TEXT_CT, null, new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(AppendOutcome.Status.NOT_FOUND, outcome.status());
    }

    @Test
    void testAppendStreamExpired() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, 1L, null);
        store.create(URL, config, null);

        Clock futureClock = Clock.fixed(Instant.parse("2024-01-01T00:00:02Z"), ZoneId.of("UTC"));
        BlockingFileStreamStore futureStore = new BlockingFileStreamStore(tempDir, futureClock);
        
        AppendOutcome outcome = futureStore.append(URL, TEXT_CT, null, new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(AppendOutcome.Status.NOT_FOUND, outcome.status());
        futureStore.close();
    }

    @Test
    void testAppendContentTypeMismatch() throws Exception {
        StreamConfig config = new StreamConfig(JSON_CT, null, null);
        store.create(URL, config, null);

        AppendOutcome outcome = store.append(URL, TEXT_CT, null, new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
        assertEquals(AppendOutcome.Status.CONFLICT, outcome.status());
    }

    @Test
    void testAppendSeqRegression() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        
        store.append(URL, TEXT_CT, "10", new ByteArrayInputStream("A".getBytes(StandardCharsets.UTF_8)));
        AppendOutcome outcome = store.append(URL, TEXT_CT, "05", new ByteArrayInputStream("B".getBytes(StandardCharsets.UTF_8)));
        
        assertEquals(AppendOutcome.Status.CONFLICT, outcome.status());
    }

    @Test
    void testReadStream() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        store.append(URL, TEXT_CT, null, new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));

        ReadOutcome outcome = store.read(URL, new Offset(LexiLong.encode(0)), 100);
        assertEquals(ReadOutcome.Status.OK, outcome.status());
        assertTrue(outcome.fileRegion().isPresent());
        assertEquals(0, outcome.fileRegion().get().position());
        assertEquals(5, outcome.fileRegion().get().length());
    }

    @Test
    void testReadStreamNotFound() throws Exception {
        ReadOutcome outcome = store.read(URL, new Offset(LexiLong.encode(0)), 100);
        assertEquals(ReadOutcome.Status.NOT_FOUND, outcome.status());
    }

    @Test
    void testReadStreamExpired() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, 1L, null);
        store.create(URL, config, null);
        
        Clock futureClock = Clock.fixed(Instant.parse("2024-01-01T00:00:02Z"), ZoneId.of("UTC"));
        BlockingFileStreamStore futureStore = new BlockingFileStreamStore(tempDir, futureClock);

        ReadOutcome outcome = futureStore.read(URL, new Offset(LexiLong.encode(0)), 100);
        assertEquals(ReadOutcome.Status.NOT_FOUND, outcome.status());
        futureStore.close();
    }

    @Test
    void testReadUpToDate() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        store.append(URL, TEXT_CT, null, new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));

        ReadOutcome outcome = store.read(URL, new Offset(LexiLong.encode(5)), 100);
        assertEquals(ReadOutcome.Status.OK, outcome.status());
        assertTrue(outcome.upToDate());
        assertEquals(0, outcome.body().length);
    }

    @Test
    void testDeleteStream() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        assertTrue(store.delete(URL));
        assertFalse(store.delete(URL));
    }

    @Test
    void testHeadStream() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        assertTrue(store.head(URL).isPresent());
        assertTrue(store.delete(URL));
        assertFalse(store.head(URL).isPresent());
    }
    
    @Test
    void testHeadStreamExpired() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, 1L, null);
        store.create(URL, config, null);
        
        Clock futureClock = Clock.fixed(Instant.parse("2024-01-01T00:00:02Z"), ZoneId.of("UTC"));
        BlockingFileStreamStore futureStore = new BlockingFileStreamStore(tempDir, futureClock);
        
        assertTrue(futureStore.head(URL).isEmpty());
        futureStore.close();
    }

    @Test
    void testAwait() throws Exception {
        StreamConfig config = new StreamConfig(TEXT_CT, null, null);
        store.create(URL, config, null);
        
        assertFalse(store.await(URL, new Offset(LexiLong.encode(0)), Duration.ofMillis(10)));

        store.append(URL, TEXT_CT, null, new ByteArrayInputStream("Hello".getBytes(StandardCharsets.UTF_8)));
        assertTrue(store.await(URL, new Offset(LexiLong.encode(0)), Duration.ofMillis(10)));
    }
}
