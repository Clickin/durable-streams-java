package io.durablestreams.client;

import java.io.InputStream;

/**
 * Abstraction for the underlying HTTP transport.
 *
 * <p>Allows plugging in different HTTP clients (e.g. JDK HttpClient, OkHttp, Apache HC).
 * Implementations must handle the low-level HTTP exchange.
 */
public interface DurableStreamsTransport {
    /**
     * Sends a request and discards the response body.
     *
     * @param request the request to send
     * @return the response with void body
     * @throws Exception if an error occurs
     */
    TransportResponse<Void> sendDiscarding(TransportRequest request) throws Exception;

    /**
     * Sends a request and returns the response body as bytes.
     *
     * @param request the request to send
     * @return the response with byte array body
     * @throws Exception if an error occurs
     */
    TransportResponse<byte[]> sendBytes(TransportRequest request) throws Exception;

    /**
     * Sends a request and returns the response body as an InputStream.
     *
     * @param request the request to send
     * @return the response with InputStream body
     * @throws Exception if an error occurs
     */
    TransportResponse<InputStream> sendStream(TransportRequest request) throws Exception;
}
