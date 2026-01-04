package io.github.clickin.server.core;

import io.github.clickin.core.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DurableStreamsValidationTest {

    private DurableStreamsHandler handler;
    private URI stream;

    @BeforeEach
    void setUp() {
        handler = DurableStreamsHandler.builder(new InMemoryStreamStore())
                .longPollTimeout(Duration.ofMillis(10))
                .sseMaxDuration(Duration.ofSeconds(1))
                .build();
        stream = URI.create("http://localhost/streams/validation");
        handler.handle(request(
                HttpMethod.PUT,
                stream,
                headers(Protocol.H_CONTENT_TYPE, "text/plain; charset=utf-8"),
                null
        ));
    }

    @Test
    void rejectsDuplicateOffsetParam() {
        ServerResponse resp = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?offset=-1&offset=0"),
                Map.of(),
                null
        ));

        assertThat(resp.status()).isEqualTo(400);
        assertThat(firstHeader(resp, "X-Error")).isEqualTo("duplicate offset parameter");
    }

    @Test
    void rejectsEmptyOffsetParam() {
        ServerResponse resp = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?offset="),
                Map.of(),
                null
        ));

        assertThat(resp.status()).isEqualTo(400);
        assertThat(firstHeader(resp, "X-Error")).isEqualTo("offset must not be empty");
    }

    @Test
    void rejectsInvalidOffsetValue() {
        ServerResponse resp = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?offset=bad,offset"),
                Map.of(),
                null
        ));

        assertThat(resp.status()).isEqualTo(400);
    }

    @Test
    void longPollRequiresOffset() {
        ServerResponse resp = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?live=long-poll"),
                Map.of(),
                null
        ));

        assertThat(resp.status()).isEqualTo(400);
        assertThat(firstHeader(resp, "X-Error")).isEqualTo("offset required for long-poll");
    }

    @Test
    void sseRequiresOffset() {
        ServerResponse resp = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?live=sse"),
                Map.of(),
                null
        ));

        assertThat(resp.status()).isEqualTo(400);
        assertThat(firstHeader(resp, "X-Error")).isEqualTo("offset required for sse");
    }

    @Test
    void rejectsTtlAndExpiresAtTogether() {
        URI ttlStream = URI.create("http://localhost/streams/ttl-both");
        ServerResponse resp = handler.handle(request(
                HttpMethod.PUT,
                ttlStream,
                headers(
                        Protocol.H_CONTENT_TYPE, "text/plain",
                        Protocol.H_STREAM_TTL, "60",
                        Protocol.H_STREAM_EXPIRES_AT, Instant.now().toString()
                ),
                null
        ));

        assertThat(resp.status()).isEqualTo(400);
        assertThat(firstHeader(resp, "X-Error")).isEqualTo("Stream-TTL and Stream-Expires-At both set");
    }

    @Test
    void rejectsInvalidTtlValues() {
        URI ttlStream = URI.create("http://localhost/streams/ttl-invalid");
        ServerResponse resp = handler.handle(request(
                HttpMethod.PUT,
                ttlStream,
                headers(
                        Protocol.H_CONTENT_TYPE, "text/plain",
                        Protocol.H_STREAM_TTL, "01"
                ),
                null
        ));

        assertThat(resp.status()).isEqualTo(400);
    }

    @Test
    void rejectsInvalidExpiresAt() {
        URI expStream = URI.create("http://localhost/streams/expires-invalid");
        ServerResponse resp = handler.handle(request(
                HttpMethod.PUT,
                expStream,
                headers(
                        Protocol.H_CONTENT_TYPE, "text/plain",
                        Protocol.H_STREAM_EXPIRES_AT, "not-a-time"
                ),
                null
        ));

        assertThat(resp.status()).isEqualTo(400);
    }

    @Test
    void sseAcceptsTextContentTypeWithCharset() {
        ServerResponse resp = handler.handle(request(
                HttpMethod.GET,
                URI.create(stream + "?offset=-1&live=sse"),
                Map.of(),
                null
        ));

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body()).isInstanceOf(ResponseBody.Sse.class);
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
