package io.durablestreams.client;

import io.durablestreams.core.Protocol;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DurableStreamsClientBuilderTest {

    @Test
    void builderUsesCustomTransport() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        transport.enqueueDiscarding(new TransportResponse<>(201, Map.of(Protocol.H_STREAM_NEXT_OFFSET, List.of("o1")), null));

        DurableStreamsClient client = DurableStreamsClient.builder()
                .transport(transport)
                .build();

        CreateRequest request = new CreateRequest(URI.create("http://localhost/streams/test"), "application/json", Map.of(), null);
        CreateResult result = client.create(request);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.nextOffset().value()).isEqualTo("o1");
        assertThat(transport.lastRequest.method()).isEqualTo("PUT");
        assertThat(firstHeader(transport.lastRequest.headers(), Protocol.H_CONTENT_TYPE)).isEqualTo("application/json");
    }

    @Test
    void readCatchUpDefaultsOffsetToBeginning() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        transport.enqueueBytes(new TransportResponse<>(200, Map.of(Protocol.H_STREAM_NEXT_OFFSET, List.of("o2")), new byte[0]));

        DurableStreamsClient client = DurableStreamsClient.builder()
                .transport(transport)
                .build();

        ReadRequest request = new ReadRequest(URI.create("http://localhost/streams/test"), null, null);
        ReadResult result = client.readCatchUp(request);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.nextOffset().value()).isEqualTo("o2");
        assertThat(transport.lastRequest.method()).isEqualTo("GET");
        assertThat(transport.lastRequest.url().toString()).contains("offset=-1");
    }

    private static String firstHeader(Map<String, ? extends Iterable<String>> headers, String name) {
        if (headers == null) return null;
        Iterable<String> values = headers.get(name);
        if (values == null) return null;
        for (String v : values) {
            return v;
        }
        return null;
    }

    private static final class RecordingTransport implements DurableStreamsTransport {
        private TransportResponse<Void> nextDiscarding;
        private TransportResponse<byte[]> nextBytes;
        private TransportResponse<InputStream> nextStream;
        private TransportRequest lastRequest;

        void enqueueDiscarding(TransportResponse<Void> response) {
            this.nextDiscarding = response;
        }

        void enqueueBytes(TransportResponse<byte[]> response) {
            this.nextBytes = response;
        }

        void enqueueStream(TransportResponse<InputStream> response) {
            this.nextStream = response;
        }

        @Override
        public TransportResponse<Void> sendDiscarding(TransportRequest request) {
            lastRequest = request;
            return nextDiscarding;
        }

        @Override
        public TransportResponse<byte[]> sendBytes(TransportRequest request) {
            lastRequest = request;
            return nextBytes;
        }

        @Override
        public TransportResponse<InputStream> sendStream(TransportRequest request) {
            lastRequest = request;
            return nextStream;
        }
    }
}
