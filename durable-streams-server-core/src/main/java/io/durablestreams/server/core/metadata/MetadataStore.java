package io.durablestreams.server.core.metadata;

import java.io.Closeable;
import java.net.URI;
import java.util.Optional;

public interface MetadataStore extends Closeable {
    Optional<FileStreamMetadata> get(URI url);

    void put(URI url, FileStreamMetadata meta);

    boolean delete(URI url);
}
