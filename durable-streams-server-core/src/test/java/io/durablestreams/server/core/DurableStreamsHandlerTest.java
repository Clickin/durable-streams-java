package io.durablestreams.server.core;

import io.durablestreams.core.Protocol;
import io.durablestreams.server.spi.CursorPolicy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DurableStreamsHandlerTest {

    @Test
    void createAppendReadRoundTrip() {
        DurableStreamsHandler handler = handlerWithTimeouts();
        URI stream = URI.create("http://localhost/streams/test");

        ServerResponse created = handler.handle(request(
                HttpMethod.PUT,
                stream,
                headers("Content-Type", "application/octet-stream"),
                "hello".getBytes()
        ));
        assertThat(created.status()).isIn(200, 201);
        assertThat(firstHeader(created, Protocol.H_STREAM_NEXT_OFFSET)).isNotNull();

        ServerResponse appended = handler.handle(request(
                HttpMethod.POST,
                stream,
                headers("Content-Type", "application/octet-stream"),
                "world".getBytes()
        ));
        assertThat(appended.status()).isEqualTo(204);
        assertThat(firstHeader(appended, Protocol.H_STREAM_NEXT_OFFSET)).isNotNull();

        ServerResponse read = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?offset=-1"),
                Map.of(),
                null
        ));
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body()).isInstanceOf(ResponseBody.Bytes.class);
        byte[] bytes = ((ResponseBody.Bytes) read.body()).bytes();
        assertThat(new String(bytes)).isEqualTo("helloworld");
        assertThat(firstHeader(read, Protocol.H_STREAM_UP_TO_DATE)).isEqualTo("true");
    }

    @Test
    void returnsNotModifiedWhenEtagMatches() {
        DurableStreamsHandler handler = handlerWithTimeouts();
        URI stream = URI.create("http://localhost/streams/etag");

        handler.handle(request(
                HttpMethod.PUT,
                stream,
                headers("Content-Type", "application/octet-stream"),
                null
        ));

        handler.handle(request(
                HttpMethod.POST,
                stream,
                headers("Content-Type", "application/octet-stream"),
                "data".getBytes()
        ));

        ServerResponse first = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?offset=-1"),
                Map.of(),
                null
        ));
        String etag = firstHeader(first, Protocol.H_ETAG);
        assertThat(etag).isNotNull();

        ServerResponse notModified = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?offset=-1"),
                headers(Protocol.H_IF_NONE_MATCH, etag),
                null
        ));
        assertThat(notModified.status()).isEqualTo(304);
    }

    @Test
    void longPollTimeoutReturnsNoContentWithCursor() {
        DurableStreamsHandler handler = handlerWithTimeouts();
        URI stream = URI.create("http://localhost/streams/longpoll");

        handler.handle(request(
                HttpMethod.PUT,
                stream,
                headers("Content-Type", "application/octet-stream"),
                null
        ));

        ServerResponse resp = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?offset=-1&live=long-poll"),
                Map.of(),
                null
        ));

        assertThat(resp.status()).isEqualTo(204);
        assertThat(firstHeader(resp, Protocol.H_STREAM_UP_TO_DATE)).isEqualTo("true");
        assertThat(firstHeader(resp, Protocol.H_STREAM_CURSOR)).isNotNull();
    }

    @Test
    void sseRejectsIncompatibleContentType() {
        DurableStreamsHandler handler = handlerWithTimeouts();
        URI stream = URI.create("http://localhost/streams/sse");

        handler.handle(request(
                HttpMethod.PUT,
                stream,
                headers("Content-Type", "application/octet-stream"),
                null
        ));

        ServerResponse resp = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?offset=-1&live=sse"),
                Map.of(),
                null
        ));

        assertThat(resp.status()).isEqualTo(400);
    }

    private DurableStreamsHandler handlerWithTimeouts() {
        return new DurableStreamsHandler(
                new InMemoryStreamStore(),
                new CursorPolicy(Clock.systemUTC()),
                CachePolicy.defaultPrivate(),
                Duration.ofMillis(25),
                Duration.ofSeconds(1),
                1024,
                Clock.systemUTC()
        );
    }

    private static ServerRequest request(HttpMethod method, URI uri, Map<String, List<String>> headers, byte[] body) {
        return new ServerRequest(method, uri, headers, body == null ? null : new ByteArrayInputStream(body));
    }

    private static Map<String, List<String>> headers(String... kv) {
        if (kv == null || kv.length == 0) return Map.of();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put(kv[i], List.of(kv[i + 1]));
        }
        return out;
    }

    private static String firstHeader(ServerResponse response, String name) {
        List<String> values = response.headers().get(name);
        if (values == null || values.isEmpty()) return null;
        return values.get(0);
    }
}
