package io.durablestreams.server.spi;

import java.util.*;

/**
 * Registry that resolves a {@link StreamCodec} for a given Content-Type.
 *
 * <p>Use {@link #builder()} to create a registry with explicit codec registration:
 * <pre>{@code
 * StreamCodecRegistry registry = StreamCodecRegistry.builder()
 *     .register(JacksonJsonStreamCodec.INSTANCE)
 *     .build();
 * }</pre>
 *
 * <p>This approach is GraalVM native-image friendly as it avoids ServiceLoader.
 */
@FunctionalInterface
public interface StreamCodecRegistry {

    /**
     * Find a codec for the given content type.
     *
     * @param contentType the content type (e.g. "application/json")
     * @return the codec if registered
     */
    Optional<StreamCodec> find(String contentType);

    /**
     * Creates a new builder for constructing a registry with explicit codec registration.
     *
     * <p>This is the recommended approach for GraalVM native-image compatibility.
     *
     * @return a new builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a registry containing only the built-in byte codec (for non-JSON content types).
     *
     * @return a minimal registry with byte codec only
     */
    static StreamCodecRegistry bytesOnly() {
        return contentType -> Optional.empty();
    }

    /**
     * Builder for creating a {@link StreamCodecRegistry} with explicit codec registration.
     */
    final class Builder {
        private final Map<String, StreamCodec> codecs = new HashMap<>();

        private Builder() {}

        /**
         * Register a codec. The codec's {@link StreamCodec#contentType()} determines
         * which content type it handles.
         *
         * @param codec the codec to register
         * @return this builder
         */
        public Builder register(StreamCodec codec) {
            Objects.requireNonNull(codec, "codec");
            String ct = codec.contentType();
            if (ct == null || ct.isBlank()) {
                throw new IllegalArgumentException("codec contentType must not be null or blank");
            }
            codecs.put(normalizeContentType(ct), codec);
            return this;
        }

        /**
         * Register multiple codecs.
         *
         * @param codecs the codecs to register
         * @return this builder
         */
        public Builder registerAll(Iterable<? extends StreamCodec> codecs) {
            for (StreamCodec codec : codecs) {
                register(codec);
            }
            return this;
        }

        /**
         * Build the registry.
         *
         * @return an immutable registry containing the registered codecs
         */
        public StreamCodecRegistry build() {
            Map<String, StreamCodec> snapshot = Map.copyOf(codecs);
            return contentType -> {
                String normalized = normalizeContentType(contentType);
                if (normalized.isEmpty()) return Optional.empty();
                return Optional.ofNullable(snapshot.get(normalized));
            };
        }

        private static String normalizeContentType(String contentType) {
            if (contentType == null) return "";
            int semi = contentType.indexOf(';');
            String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
            return base.trim().toLowerCase(Locale.ROOT);
        }
    }
}
