package io.github.clickin.server.spi;

import java.util.List;

/**
 * ServiceLoader provider for {@link StreamCodec}.
 *
 * <p>Modules such as {@code durable-streams-json-jackson} should register implementations
 * via {@code META-INF/services}.
 */
public interface StreamCodecProvider {
    List<StreamCodec> codecs();
}
