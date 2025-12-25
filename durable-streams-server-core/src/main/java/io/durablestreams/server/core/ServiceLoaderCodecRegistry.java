package io.durablestreams.server.core;

import io.durablestreams.server.spi.StreamCodec;
import io.durablestreams.server.spi.StreamCodecProvider;
import io.durablestreams.server.spi.StreamCodecRegistry;

import java.util.*;

/**
 * {@link StreamCodecRegistry} backed by {@link java.util.ServiceLoader}.
 *
 * <p>Resolves:
 * <ul>
 *   <li>{@code application/json} via registered codecs (if present)</li>
 *   <li>All other content types via {@link ByteStreamCodec} fallback</li>
 * </ul>
 */
public final class ServiceLoaderCodecRegistry implements StreamCodecRegistry {

    private final Map<String, StreamCodec> byContentType;

    public ServiceLoaderCodecRegistry(ClassLoader cl) {
        Objects.requireNonNull(cl, "cl");
        Map<String, StreamCodec> map = new HashMap<>();

        ServiceLoader<StreamCodecProvider> loader = ServiceLoader.load(StreamCodecProvider.class, cl);
        for (StreamCodecProvider p : loader) {
            for (StreamCodec c : p.codecs()) {
                if (c == null || c.contentType() == null) continue;
                map.put(c.contentType().toLowerCase(Locale.ROOT), c);
            }
        }
        this.byContentType = Map.copyOf(map);
    }

    public static ServiceLoaderCodecRegistry defaultRegistry() {
        return new ServiceLoaderCodecRegistry(Thread.currentThread().getContextClassLoader());
    }

    @Override
    public Optional<StreamCodec> find(String contentType) {
        if (contentType == null) return Optional.empty();
        return Optional.ofNullable(byContentType.get(contentType.toLowerCase(Locale.ROOT)));
    }

    StreamCodec fallbackBytes() {
        return ByteStreamCodec.INSTANCE;
    }
}
