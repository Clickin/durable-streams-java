package io.github.clickin.server.spi;

import io.github.clickin.core.Offset;

/**
 * Strategy for generating strictly increasing, lexicographically sortable offsets.
 *
 * <p>Implementations can use different algorithms (ULID, base36, timestamps)
 * while maintaining protocol requirements.
 */
public interface OffsetGenerator {
    
    /**
     * Generate the next offset for a stream.
     *
     * @param previous previous offset, or null for the first offset
     * @param size current size of the stream (bytes or messages depending on codec)
     * @return a new offset that is greater than all previous offsets
     * @throws IllegalArgumentException if offset generation fails
     */
    Offset next(Offset previous, long size) throws IllegalArgumentException;
    
    /**
     * Encode an offset to its opaque string representation.
     *
     * @param offset the offset to encode
     * @return opaque string token suitable for URLs and headers
     */
    String encode(Offset offset);
    
    /**
     * Decode an opaque string back to an Offset.
     *
     * @param token the opaque string token
     * @return the corresponding Offset
     * @throws IllegalArgumentException if token is malformed
     */
    Offset decode(String token) throws IllegalArgumentException;
}