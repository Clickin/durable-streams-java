package io.durablestreams.server.core.metadata;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link MetadataStore} using {@link ConcurrentHashMap}.
 *
 * <p>This implementation does not persist metadata to disk, making it suitable for:
 * <ul>
 *   <li>Testing and development</li>
 *   <li>Environments where JVM args for LMDB reflection are not available</li>
 *   <li>Short-lived streams that don't require persistence across restarts</li>
 * </ul>
 *
 * <p>For persistent metadata storage, use {@link LmdbMetadataStore} with appropriate
 * JVM arguments to enable reflection access.
 */
public final class InMemoryMetadataStore implements MetadataStore {

    private final ConcurrentHashMap<URI, FileStreamMetadata> store = new ConcurrentHashMap<>();

    @Override
    public Optional<FileStreamMetadata> get(URI url) {
        Objects.requireNonNull(url, "url");
        return Optional.ofNullable(store.get(url));
    }

    @Override
    public void put(URI url, FileStreamMetadata meta) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(meta, "meta");
        store.put(url, meta);
    }

    @Override
    public boolean delete(URI url) {
        Objects.requireNonNull(url, "url");
        return store.remove(url) != null;
    }

    @Override
    public void close() throws IOException {
        store.clear();
    }
}
