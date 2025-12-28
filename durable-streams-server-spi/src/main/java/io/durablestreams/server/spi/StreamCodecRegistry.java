package io.durablestreams.server.spi;

import java.util.Optional;

/**
 * Registry that resolves a {@link StreamCodec} for a given Content-Type.
 */
@FunctionalInterface
public interface StreamCodecRegistry {
    Optional<StreamCodec> find(String contentType);
}
