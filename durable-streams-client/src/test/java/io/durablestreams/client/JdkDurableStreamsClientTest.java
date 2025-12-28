package io.durablestreams.client;

import io.durablestreams.core.Offset;
import io.durablestreams.core.StreamEvent;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdkDurableStreamsClientTest {

    private MockWebServer server;
    private JdkDurableStreamsClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new JdkDurableStreamsClient(HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void createReturnsNextOffset() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Stream-Next-Offset", "abc"));

        CreateRequest request = new CreateRequest(server.url("/stream/test").uri(), "application/json", Map.of(), null);
        CreateResult result = client.create(request);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.nextOffset().value()).isEqualTo("abc");
    }

    @Test
    void appendReturnsNextOffset() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(204)
                .addHeader("Stream-Next-Offset", "def"));

        AppendRequest request = new AppendRequest(server.url("/stream/test").uri(), "application/json", Map.of(), new java.io.ByteArrayInputStream("x".getBytes()));
        AppendResult result = client.append(request);

        assertThat(result.status()).isEqualTo(204);
        assertThat(result.nextOffset().value()).isEqualTo("def");
    }

    @Test
    void headParsesMetadata() throws Exception {
        Instant expiresAt = Instant.parse("2025-01-01T00:00:00Z");
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Stream-Next-Offset", "o1")
                .addHeader("Stream-TTL", "60")
                .addHeader("Stream-Expires-At", expiresAt.toString()));

        HeadResult result = client.head(server.url("/stream/test").uri());

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.contentType()).isEqualTo("application/json");
        assertThat(result.nextOffset().value()).isEqualTo("o1");
        assertThat(result.ttlSeconds()).isEqualTo(60L);
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void readCatchUpUsesIfNoneMatch() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(304)
                .addHeader("Stream-Next-Offset", "o2")
                .addHeader("ETag", "\"s:o1:o2\"")
                .addHeader("Stream-Up-To-Date", "true"));

        ReadRequest request = new ReadRequest(server.url("/stream/test").uri(), new Offset("o1"), "\"s:o1:o2\"");
        ReadResult result = client.readCatchUp(request);

        assertThat(result.status()).isEqualTo(304);
        assertThat(result.nextOffset().value()).isEqualTo("o2");
        assertThat(result.upToDate()).isTrue();

        var recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded.getHeader("If-None-Match")).isEqualTo("\"s:o1:o2\"");
        assertThat(recorded.getPath()).contains("offset=o1");
    }

    @Test
    void longPollEmitsUpToDateOn204() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(204)
                .addHeader("Stream-Next-Offset", "o3")
                .addHeader("Stream-Up-To-Date", "true")
                .addHeader("Stream-Cursor", "c1"));
        server.enqueue(new MockResponse()
                .setResponseCode(204)
                .addHeader("Stream-Next-Offset", "o3")
                .addHeader("Stream-Up-To-Date", "true")
                .addHeader("Stream-Cursor", "c1"));

        LiveLongPollRequest request = new LiveLongPollRequest(server.url("/stream/test").uri(), new Offset("o0"), null, Duration.ofMillis(200));
        Flow.Publisher<StreamEvent> pub = client.subscribeLongPoll(request);

        TestSubscriber subscriber = new TestSubscriber(1);
        pub.subscribe(subscriber);
        assertThat(subscriber.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(subscriber.events()).hasSize(1);
        assertThat(subscriber.events().get(0)).isInstanceOf(StreamEvent.UpToDate.class);
        StreamEvent.UpToDate utd = (StreamEvent.UpToDate) subscriber.events().get(0);
        assertThat(utd.nextOffset().value()).isEqualTo("o3");
    }

    @Test
    void sseParsesDataAndControl() throws Exception {
        String body = "event: data\n" +
                "data: hello\n\n" +
                "event: control\n" +
                "data: {\"streamNextOffset\":\"o4\",\"streamCursor\":\"c2\"}\n\n";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(body));

        LiveSseRequest request = new LiveSseRequest(server.url("/stream/test").uri(), new Offset("o0"));
        Flow.Publisher<StreamEvent> pub = client.subscribeSse(request);

        TestSubscriber subscriber = new TestSubscriber(2);
        pub.subscribe(subscriber);
        assertThat(subscriber.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(subscriber.events()).hasSize(2);
        assertThat(subscriber.events().get(0)).isInstanceOf(StreamEvent.Data.class);
        assertThat(subscriber.events().get(1)).isInstanceOf(StreamEvent.Control.class);
        StreamEvent.Control control = (StreamEvent.Control) subscriber.events().get(1);
        assertThat(control.streamNextOffset().value()).isEqualTo("o4");
        assertThat(control.streamCursor().orElse(null)).isEqualTo("c2");
    }

    private static final class TestSubscriber implements Flow.Subscriber<StreamEvent> {
        private final List<StreamEvent> events = new ArrayList<>();
        private final CountDownLatch latch;
        private Flow.Subscription subscription;

        private TestSubscriber(int expected) {
            this.latch = new CountDownLatch(expected);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(StreamEvent item) {
            events.add(item);
            latch.countDown();
            if (latch.getCount() == 0 && subscription != null) {
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        private List<StreamEvent> events() {
            return events;
        }
    }
}