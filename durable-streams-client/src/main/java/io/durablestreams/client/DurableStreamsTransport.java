package io.durablestreams.client;

import java.io.InputStream;

public interface DurableStreamsTransport {
    TransportResponse<Void> sendDiscarding(TransportRequest request) throws Exception;
    TransportResponse<byte[]> sendBytes(TransportRequest request) throws Exception;
    TransportResponse<InputStream> sendStream(TransportRequest request) throws Exception;
}
