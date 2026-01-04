package io.github.clickin.server.spi;

import java.io.InputStream;

/**
 * Content-Type specific codec for reference stores and adapters.
 *
 * <p>This SPI exists to keep the protocol engine/framework bindings independent of any specific
 * serialization library (Jackson, Gson, etc.). Implementations may live in separate modules.
 *
 * <p>Offset semantics are store-defined. For the provided in-memory reference store:
 * <ul>
 *   <li>Byte/text streams: offset represents byte position</li>
 *   <li>application/json streams: offset represents message index</li>
 * </ul>
 */
public interface StreamCodec {

    /**
     * Exact Content-Type handled by this codec (e.g. {@code application/json}).
     */
    String contentType();

    /**
     * Marker interface for codec-specific mutable state.
     */
    interface State {}

    /**
     * Create an empty state for a new stream.
     */
    State createEmpty();

    /**
     * Apply initial content on PUT.
     *
     * <p>Implementations may treat empty body as "no-op".
     */
    void applyInitial(State state, InputStream body) throws Exception;

    /**
     * Append data on POST.
     *
     * <p>Implementations MUST reject empty bodies (protocol requirement).
     */
    void append(State state, InputStream body) throws Exception;

    /**
     * Read a chunk from the state.
     *
     * @param start  start position (bytes or message index depending on codec)
     * @param limit  max bytes (byte streams) or max messages (json streams)
     */
    ReadChunk read(State state, long start, int limit) throws Exception;

    /**
     * Current size of the stream state (bytes or messages depending on codec).
     */
    long size(State state);

    /**
     * A read chunk result.
     *
     * @param body bytes to return as HTTP response body
     * @param nextPosition next position after this chunk
     * @param upToDate whether the returned chunk reaches the end of stream
     */
    record ReadChunk(byte[] body, long nextPosition, boolean upToDate) {}
}
