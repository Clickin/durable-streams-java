package io.github.clickin.json.jackson;

import io.github.clickin.server.spi.StreamCodec;
import io.github.clickin.server.spi.StreamCodecProvider;

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
