package io.durablestreams.server.core.metadata;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.StreamConfig;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.Txn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class LmdbMetadataStore implements MetadataStore {
    private static final int MAX_KEY_SIZE = 511;
    private static final int MAX_DBS = 1;
    private static final long DEFAULT_MAP_SIZE = 256L * 1024 * 1024;

    private static final ThreadLocal<ByteBuffer> KEY_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(MAX_KEY_SIZE));

    private final Env<ByteBuffer> env;
    private final Dbi<ByteBuffer> streams;

    public LmdbMetadataStore(Path baseDir) {
        this(baseDir, DEFAULT_MAP_SIZE, 0);
    }

    public LmdbMetadataStore(Path baseDir, long mapSize) {
        this(baseDir, mapSize, 0);
    }

    public LmdbMetadataStore(Path baseDir, int maxReaders) {
        this(baseDir, DEFAULT_MAP_SIZE, maxReaders);
    }

    public LmdbMetadataStore(Path baseDir, long mapSize, int maxReaders) {
        Objects.requireNonNull(baseDir, "baseDir");
        if (mapSize <= 0) {
            throw new IllegalArgumentException("mapSize must be positive");
        }
        if (maxReaders < 0) {
            throw new IllegalArgumentException("maxReaders must be non-negative");
        }
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create LMDB directory", e);
        }
        Env.Builder<ByteBuffer> builder = Env.create()
                .setMapSize(mapSize)
                .setMaxDbs(MAX_DBS);
        if (maxReaders > 0) {
            builder.setMaxReaders(maxReaders);
        }
        this.env = builder.open(baseDir.toFile());
        this.streams = env.openDbi("streams", DbiFlags.MDB_CREATE);
    }

    @Override
    public Optional<FileStreamMetadata> get(java.net.URI url) {
        Objects.requireNonNull(url, "url");
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer key = encodeKey(url.toString());
            ByteBuffer value = streams.get(txn, key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(decodeValue(value));
        }
    }

    @Override
    public void put(java.net.URI url, FileStreamMetadata meta) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(meta, "meta");
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            ByteBuffer key = encodeKey(url.toString());
            ByteBuffer value = encodeValue(meta);
            streams.put(txn, key, value);
            txn.commit();
        }
        env.sync(true);
    }

    @Override
    public boolean delete(java.net.URI url) {
        Objects.requireNonNull(url, "url");
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            ByteBuffer key = encodeKey(url.toString());
            boolean removed = streams.delete(txn, key);
            txn.commit();
            if (removed) {
                env.sync(true);
            }
            return removed;
        }
    }

    @Override
    public void close() {
        streams.close();
        env.close();
    }

    private static ByteBuffer encodeKey(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_KEY_SIZE) {
            throw new IllegalArgumentException("Stream URL is too long for LMDB key");
        }
        ByteBuffer buffer = KEY_BUFFER.get();
        buffer.clear();
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer encodeValue(FileStreamMetadata meta) {
        String streamId = meta.streamId();
        String contentType = meta.config().contentType();
        String nextOffset = meta.nextOffset().value();
        Long ttlSeconds = meta.config().ttlSeconds().orElse(null);
        Instant configExpiresAt = meta.config().expiresAt().orElse(null);
        Instant expiresAt = meta.expiresAt();

        int size = 1
                + sizeOfString(streamId)
                + sizeOfString(contentType)
                + sizeOfString(nextOffset)
                + (ttlSeconds == null ? 0 : Long.BYTES)
                + (configExpiresAt == null ? 0 : Long.BYTES)
                + (expiresAt == null ? 0 : Long.BYTES);

        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        byte flags = 0;
        if (ttlSeconds != null) flags |= 0x01;
        if (configExpiresAt != null) flags |= 0x02;
        if (expiresAt != null) flags |= 0x04;
        buffer.put(flags);

        writeString(buffer, streamId);
        writeString(buffer, contentType);
        writeString(buffer, nextOffset);

        if (ttlSeconds != null) buffer.putLong(ttlSeconds);
        if (configExpiresAt != null) buffer.putLong(configExpiresAt.toEpochMilli());
        if (expiresAt != null) buffer.putLong(expiresAt.toEpochMilli());

        buffer.flip();
        return buffer;
    }

    private static FileStreamMetadata decodeValue(ByteBuffer buffer) {
        byte flags = buffer.get();
        String streamId = readString(buffer);
        String contentType = readString(buffer);
        String nextOffset = readString(buffer);

        Long ttlSeconds = null;
        if ((flags & 0x01) != 0) {
            ttlSeconds = buffer.getLong();
        }

        Instant configExpiresAt = null;
        if ((flags & 0x02) != 0) {
            configExpiresAt = Instant.ofEpochMilli(buffer.getLong());
        }

        Instant expiresAt = null;
        if ((flags & 0x04) != 0) {
            expiresAt = Instant.ofEpochMilli(buffer.getLong());
        }

        StreamConfig config = new StreamConfig(contentType, ttlSeconds, configExpiresAt);
        return new FileStreamMetadata(streamId, config, new Offset(nextOffset), expiresAt);
    }

    private static void writeString(ByteBuffer buffer, String value) {
        if (value == null) {
            buffer.putShort((short) 0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Short.MAX_VALUE) {
            throw new IllegalArgumentException("String too long for metadata encoding");
        }
        buffer.putShort((short) bytes.length);
        buffer.put(bytes);
    }

    private static String readString(ByteBuffer buffer) {
        short len = buffer.getShort();
        if (len == 0) return null;
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int sizeOfString(String value) {
        if (value == null) return Short.BYTES;
        int len = value.getBytes(StandardCharsets.UTF_8).length;
        return Short.BYTES + len;
    }
}
