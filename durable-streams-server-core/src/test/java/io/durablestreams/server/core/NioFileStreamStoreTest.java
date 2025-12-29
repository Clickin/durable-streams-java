package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link NioFileStreamStore}.
 */
class NioFileStreamStoreTest {

    @TempDir
    Path tempDir;

    private NioFileStreamStore store;

    @BeforeEach
    void setUp() {
        store = new NioFileStreamStore(tempDir);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void createAndReadStream() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test1");
        StreamConfig config = new StreamConfig("application/octet-stream", null, null);
        byte[] data = "hello file".getBytes(StandardCharsets.UTF_8);

        // Create with initial data
        CreateOutcome created = store.create(url, config, ByteBuffer.wrap(data))
                .get(5, TimeUnit.SECONDS);

        assertThat(created.status()).isEqualTo(CreateOutcome.Status.CREATED);
        assertThat(created.metadata()).isNotNull();
        assertThat(created.nextOffset()).isNotNull();

        // Read back
        ReadOutcome read = store.read(url, Offset.beginning(), 1024)
                .get(5, TimeUnit.SECONDS);

        assertThat(read.status()).isEqualTo(ReadOutcome.Status.OK);
        assertThat(new String(readBodyBytes(read), StandardCharsets.UTF_8)).isEqualTo("hello file");
        assertThat(read.upToDate()).isTrue();


        assertThat(read.etag()).isNotEmpty();
    }

    @Test
    void createEmptyStreamAndAppend() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test2");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        // Create empty
        CreateOutcome created = store.create(url, config, null)
                .get(5, TimeUnit.SECONDS);

        assertThat(created.status()).isEqualTo(CreateOutcome.Status.CREATED);

        // Append
        byte[] data = "appended content".getBytes(StandardCharsets.UTF_8);
        AppendOutcome appended = store.append(url, "text/plain", null, ByteBuffer.wrap(data))
                .get(5, TimeUnit.SECONDS);

        assertThat(appended.status()).isEqualTo(AppendOutcome.Status.APPENDED);

        // Read
        ReadOutcome read = store.read(url, Offset.beginning(), 1024)
                .get(5, TimeUnit.SECONDS);
        assertThat(new String(readBodyBytes(read), StandardCharsets.UTF_8)).isEqualTo("appended content");



    }

    @Test
    void multipleAppends() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test3");
        StreamConfig config = new StreamConfig("application/octet-stream", null, null);

        store.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Multiple appends
        for (int i = 0; i < 5; i++) {
            byte[] data = ("chunk" + i).getBytes(StandardCharsets.UTF_8);
            AppendOutcome appended = store.append(url, "application/octet-stream", null, ByteBuffer.wrap(data))
                    .get(5, TimeUnit.SECONDS);
            assertThat(appended.status()).isEqualTo(AppendOutcome.Status.APPENDED);
        }

        // Read all
        ReadOutcome read = store.read(url, Offset.beginning(), 1024)
                .get(5, TimeUnit.SECONDS);

        assertThat(new String(readBodyBytes(read), StandardCharsets.UTF_8))
                .isEqualTo("chunk0chunk1chunk2chunk3chunk4");

    }

    @Test
    void headReturnsMetadata() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test4");
        StreamConfig config = new StreamConfig("text/plain", null, null);
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);

        store.create(url, config, ByteBuffer.wrap(data)).get(5, TimeUnit.SECONDS);

        Optional<StreamMetadata> meta = store.head(url).get(5, TimeUnit.SECONDS);

        assertThat(meta).isPresent();
        assertThat(meta.get().config().contentType()).isEqualTo("text/plain");
        assertThat(meta.get().internalStreamId()).isNotEmpty();
    }

    @Test
    void headReturnsEmptyForNonExistent() throws Exception {
        URI url = URI.create("http://localhost/streams/nonexistent");

        Optional<StreamMetadata> meta = store.head(url).get(5, TimeUnit.SECONDS);

        assertThat(meta).isEmpty();
    }

    @Test
    void deleteRemovesStream() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test5");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        store.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Verify exists
        assertThat(store.head(url).get(5, TimeUnit.SECONDS)).isPresent();

        // Delete
        Boolean deleted = store.delete(url).get(5, TimeUnit.SECONDS);
        assertThat(deleted).isTrue();

        // Verify gone
        assertThat(store.head(url).get(5, TimeUnit.SECONDS)).isEmpty();

        // Delete again
        deleted = store.delete(url).get(5, TimeUnit.SECONDS);
        assertThat(deleted).isFalse();
    }

    @Test
    void awaitCompletesOnDataArrival() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test6");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        store.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Start waiting in background
        CompletableFuture<Boolean> waitFuture = store.await(url, Offset.beginning(), Duration.ofSeconds(10));

        // Give it a moment
        Thread.sleep(50);
        assertThat(waitFuture.isDone()).isFalse();

        // Append data to wake up waiter
        byte[] data = "wake".getBytes(StandardCharsets.UTF_8);
        store.append(url, "text/plain", null, ByteBuffer.wrap(data)).get(5, TimeUnit.SECONDS);

        // Wait should complete with true
        Boolean result = waitFuture.get(5, TimeUnit.SECONDS);
        assertThat(result).isTrue();
    }

    @Test
    void awaitTimesOutWhenNoData() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test7");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        store.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Wait with short timeout
        Boolean result = store.await(url, Offset.beginning(), Duration.ofMillis(100))
                .get(5, TimeUnit.SECONDS);

        assertThat(result).isFalse();
    }

    @Test
    void awaitReturnsFalseForNonExistentStream() throws Exception {
        URI url = URI.create("http://localhost/streams/nonexistent");

        Boolean result = store.await(url, Offset.beginning(), Duration.ofMillis(100))
                .get(5, TimeUnit.SECONDS);

        assertThat(result).isFalse();
    }

    @Test
    void contentTypeMismatchReturnsConflict() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test8");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        store.create(url, config, null).get(5, TimeUnit.SECONDS);

        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        AppendOutcome result = store.append(url, "application/json", null, ByteBuffer.wrap(data))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(AppendOutcome.Status.CONFLICT);
    }

    @Test
    void createExistingStreamWithSameConfigReturnsMatch() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test9");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        store.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Create again with same config
        CreateOutcome second = store.create(url, config, null).get(5, TimeUnit.SECONDS);

        assertThat(second.status()).isEqualTo(CreateOutcome.Status.EXISTS_MATCH);
    }

    @Test
    void createExistingStreamWithDifferentConfigReturnsConflict() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test10");
        StreamConfig config1 = new StreamConfig("text/plain", null, null);
        StreamConfig config2 = new StreamConfig("application/json", null, null);

        store.create(url, config1, null).get(5, TimeUnit.SECONDS);

        // Create again with different config
        CreateOutcome second = store.create(url, config2, null).get(5, TimeUnit.SECONDS);

        assertThat(second.status()).isEqualTo(CreateOutcome.Status.EXISTS_CONFLICT);
    }

    @Test
    void readFromOffset() throws Exception {
        URI url = URI.create("http://localhost/streams/file-test11");
        StreamConfig config = new StreamConfig("application/octet-stream", null, null);
        byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);

        store.create(url, config, ByteBuffer.wrap(data)).get(5, TimeUnit.SECONDS);

        // Read from offset 5
        Offset offset = new Offset(LexiLong.encode(5));
        ReadOutcome read = store.read(url, offset, 1024).get(5, TimeUnit.SECONDS);

        assertThat(read.status()).isEqualTo(ReadOutcome.Status.OK);
        assertThat(new String(readBodyBytes(read), StandardCharsets.UTF_8)).isEqualTo("56789");

    }

    @Test
    void streamWithTtlExpires() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
        NioFileStreamStore timedStore = new NioFileStreamStore(tempDir.resolve("timed"), clock);

        try {
            URI url = URI.create("http://localhost/streams/ttl-test");
            StreamConfig config = new StreamConfig("text/plain", 1L, null); // 1 second TTL

            timedStore.create(url, config, null).get(5, TimeUnit.SECONDS);

            // Should exist
            assertThat(timedStore.head(url).get(5, TimeUnit.SECONDS)).isPresent();

            // Advance time past TTL
            clock.advance(Duration.ofSeconds(2));

            // Should be gone
            assertThat(timedStore.head(url).get(5, TimeUnit.SECONDS)).isEmpty();
        } finally {
            timedStore.close();
        }
    }

    @Test
    void concurrentAppendsSucceed() throws Exception {
        URI url = URI.create("http://localhost/streams/concurrent-test");
        StreamConfig config = new StreamConfig("application/octet-stream", null, null);

        store.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Launch multiple concurrent appends
        int numAppends = 10;
        CompletableFuture<?>[] futures = new CompletableFuture[numAppends];

        for (int i = 0; i < numAppends; i++) {
            final int idx = i;
            byte[] data = ("data" + idx).getBytes(StandardCharsets.UTF_8);
            futures[i] = store.append(url, "application/octet-stream", null, ByteBuffer.wrap(data));
        }

        // Wait for all
        CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

        // Verify all succeeded
        for (CompletableFuture<?> f : futures) {
            AppendOutcome outcome = (AppendOutcome) f.get();
            assertThat(outcome.status()).isEqualTo(AppendOutcome.Status.APPENDED);
        }

        // Read and verify total size
        ReadOutcome read = store.read(url, Offset.beginning(), 10000).get(5, TimeUnit.SECONDS);
        assertThat(readBodyBytes(read).length).isEqualTo(numAppends * 5); // "data0" = 5 bytes each
    }

    private static byte[] readBodyBytes(ReadOutcome read) {
        if (read.body() != null) {
            return read.body();
        }
        if (read.fileRegion().isEmpty()) {
            return new byte[0];
        }
        io.durablestreams.server.spi.ReadOutcome.FileRegion region = read.fileRegion().get();
        try (var channel = java.nio.channels.FileChannel.open(region.path(), java.nio.file.StandardOpenOption.READ)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(region.length());
            channel.position(region.position());
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            return buffer.array();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant) {
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
