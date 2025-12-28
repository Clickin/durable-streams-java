package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AsyncStreamStore} via {@link BlockingToAsyncAdapter}.
 */
class AsyncStreamStoreTest {

    private InMemoryStreamStore blockingStore;
    private AsyncStreamStore asyncStore;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        blockingStore = new InMemoryStreamStore();
        executor = Executors.newCachedThreadPool();
        asyncStore = new BlockingToAsyncAdapter(blockingStore, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void createAndReadStream() throws Exception {
        URI url = URI.create("http://localhost/streams/test1");
        StreamConfig config = new StreamConfig("text/plain", null, null);
        byte[] initialData = "hello".getBytes(StandardCharsets.UTF_8);

        // Create
        CreateOutcome created = asyncStore.create(url, config, ByteBuffer.wrap(initialData))
                .get(5, TimeUnit.SECONDS);

        assertThat(created.status()).isEqualTo(CreateOutcome.Status.CREATED);
        assertThat(created.nextOffset()).isNotNull();

        // Read
        ReadOutcome read = asyncStore.read(url, Offset.beginning(), 1024)
                .get(5, TimeUnit.SECONDS);

        assertThat(read.status()).isEqualTo(ReadOutcome.Status.OK);
        assertThat(new String(read.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(read.upToDate()).isTrue();
    }

    @Test
    void appendToStream() throws Exception {
        URI url = URI.create("http://localhost/streams/test2");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        // Create empty stream
        asyncStore.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Append
        byte[] data = "appended data".getBytes(StandardCharsets.UTF_8);
        AppendOutcome appended = asyncStore.append(url, "text/plain", null, ByteBuffer.wrap(data))
                .get(5, TimeUnit.SECONDS);

        assertThat(appended.status()).isEqualTo(AppendOutcome.Status.APPENDED);
        assertThat(appended.nextOffset()).isNotNull();

        // Read back
        ReadOutcome read = asyncStore.read(url, Offset.beginning(), 1024)
                .get(5, TimeUnit.SECONDS);

        assertThat(new String(read.body(), StandardCharsets.UTF_8)).isEqualTo("appended data");
    }

    @Test
    void deleteStream() throws Exception {
        URI url = URI.create("http://localhost/streams/test3");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        asyncStore.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Verify exists
        Optional<StreamMetadata> meta = asyncStore.head(url).get(5, TimeUnit.SECONDS);
        assertThat(meta).isPresent();

        // Delete
        Boolean deleted = asyncStore.delete(url).get(5, TimeUnit.SECONDS);
        assertThat(deleted).isTrue();

        // Verify gone
        meta = asyncStore.head(url).get(5, TimeUnit.SECONDS);
        assertThat(meta).isEmpty();

        // Delete again returns false
        deleted = asyncStore.delete(url).get(5, TimeUnit.SECONDS);
        assertThat(deleted).isFalse();
    }

    @Test
    void awaitCompletesWhenDataArrives() throws Exception {
        URI url = URI.create("http://localhost/streams/test4");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        asyncStore.create(url, config, null).get(5, TimeUnit.SECONDS);
        Offset initialOffset = Offset.beginning();

        // Start waiting
        CompletableFuture<Boolean> waitFuture = asyncStore.await(url, initialOffset, Duration.ofSeconds(10));

        // Should not be complete yet
        assertThat(waitFuture.isDone()).isFalse();

        // Append data
        byte[] data = "wake up".getBytes(StandardCharsets.UTF_8);
        asyncStore.append(url, "text/plain", null, ByteBuffer.wrap(data)).get(5, TimeUnit.SECONDS);

        // Wait should complete with true
        Boolean result = waitFuture.get(5, TimeUnit.SECONDS);
        assertThat(result).isTrue();
    }

    @Test
    void awaitTimesOut() throws Exception {
        URI url = URI.create("http://localhost/streams/test5");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        asyncStore.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Wait with short timeout
        Boolean result = asyncStore.await(url, Offset.beginning(), Duration.ofMillis(100))
                .get(5, TimeUnit.SECONDS);

        assertThat(result).isFalse();
    }

    @Test
    void contentTypeMismatchReturnsConflict() throws Exception {
        URI url = URI.create("http://localhost/streams/test6");
        StreamConfig config = new StreamConfig("text/plain", null, null);

        asyncStore.create(url, config, null).get(5, TimeUnit.SECONDS);

        // Try to append with wrong content type
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        AppendOutcome result = asyncStore.append(url, "application/json", null, ByteBuffer.wrap(data))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(AppendOutcome.Status.CONFLICT);
    }

    @Test
    void appendToNonExistentStreamReturnsNotFound() throws Exception {
        URI url = URI.create("http://localhost/streams/nonexistent");

        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        AppendOutcome result = asyncStore.append(url, "text/plain", null, ByteBuffer.wrap(data))
                .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(AppendOutcome.Status.NOT_FOUND);
    }

    @Test
    void readNonExistentStreamReturnsNotFound() throws Exception {
        URI url = URI.create("http://localhost/streams/nonexistent");

        ReadOutcome result = asyncStore.read(url, Offset.beginning(), 1024)
                .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ReadOutcome.Status.NOT_FOUND);
    }

    @Test
    void adapterExposesDelegate() {
        BlockingToAsyncAdapter adapter = (BlockingToAsyncAdapter) asyncStore;
        assertThat(adapter.delegate()).isSameAs(blockingStore);
        assertThat(adapter.executor()).isSameAs(executor);
    }
}
