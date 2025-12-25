package io.durablestreams.json.jackson;

import io.durablestreams.server.spi.StreamCodec;
import io.durablestreams.server.spi.StreamCodecProvider;

import java.util.List;

/**
 * ServiceLoader provider for {@link JacksonJsonStreamCodec}.
 */
public final class JacksonJsonCodecProvider implements StreamCodecProvider {
    @Override
    public List<StreamCodec> codecs() {
        return List.of(new JacksonJsonStreamCodec());
    }
}
