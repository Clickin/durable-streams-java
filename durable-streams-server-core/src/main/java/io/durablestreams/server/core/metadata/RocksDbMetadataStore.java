package io.durablestreams.server.core.metadata;

import io.durablestreams.core.Offset;
import io.durablestreams.server.spi.StreamConfig;
import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class RocksDbMetadataStore implements MetadataStore {
    private static final long DEFAULT_WRITE_BUFFER_SIZE = 64L * 1024 * 1024;
    private static final int DEFAULT_MAX_WRITE_BUFFERS = 3;

    private final RocksDB db;
    private final Options options;

    public RocksDbMetadataStore(Path baseDir) {
        this(baseDir, DEFAULT_WRITE_BUFFER_SIZE, DEFAULT_MAX_WRITE_BUFFERS);
    }

    public RocksDbMetadataStore(Path baseDir, long writeBufferSize, int maxWriteBuffers) {
        Objects.requireNonNull(baseDir, "baseDir");
        if (writeBufferSize <= 0) {
            throw new IllegalArgumentException("writeBufferSize must be positive");
        }
        if (maxWriteBuffers <= 0) {
            throw new IllegalArgumentException("maxWriteBuffers must be positive");
        }
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create RocksDB directory", e);
        }
        try {
            RocksDB.loadLibrary();
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException(
                    "RocksDB native library failed to load. Add a platform-specific RocksDB JNI dependency at runtime "
                            + "(org.rocksdb:rocksdbjni:<version>:<classifier>, e.g. linux64/win64/osx).",
                    e);
        }
        this.options = new Options()
                .setCreateIfMissing(true)
                .setWriteBufferSize(writeBufferSize)
                .setMaxWriteBufferNumber(maxWriteBuffers)
                .setCompressionType(CompressionType.LZ4_COMPRESSION);
        try {
            this.db = RocksDB.open(options, baseDir.toString());
        } catch (RocksDBException e) {
            options.close();
            throw new IllegalStateException("Failed to open RocksDB", e);
        }
    }

    @Override
    public Optional<FileStreamMetadata> get(java.net.URI url) {
        Objects.requireNonNull(url, "url");
        try {
            byte[] value = db.get(encodeKey(url.toString()));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(decodeValue(ByteBuffer.wrap(value)));
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to read metadata", e);
        }
    }

    @Override
    public void put(java.net.URI url, FileStreamMetadata meta) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(meta, "meta");
        ByteBuffer buffer = encodeValue(meta);
        byte[] value = new byte[buffer.remaining()];
        buffer.get(value);
        try {
            db.put(encodeKey(url.toString()), value);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to write metadata", e);
        }
    }

    @Override
    public boolean delete(java.net.URI url) {
        Objects.requireNonNull(url, "url");
        byte[] key = encodeKey(url.toString());
        try {
            byte[] existing = db.get(key);
            if (existing == null) {
                return false;
            }
            db.delete(key);
            return true;
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to delete metadata", e);
        }
    }

    @Override
    public void close() {
        db.close();
        options.close();
    }

    private static byte[] encodeKey(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static ByteBuffer encodeValue(FileStreamMetadata meta) {
        String streamId = meta.streamId();
        String contentType = meta.config().contentType();
        String nextOffset = meta.nextOffset().value();
        Long ttlSeconds = meta.config().ttlSeconds().orElse(null);
        Instant configExpiresAt = meta.config().expiresAt().orElse(null);
        Instant expiresAt = meta.expiresAt();
        String lastSeq = meta.lastSeq();

        int size = 1
                + sizeOfString(streamId)
                + sizeOfString(contentType)
                + sizeOfString(nextOffset)
                + (ttlSeconds == null ? 0 : Long.BYTES)
                + (configExpiresAt == null ? 0 : Long.BYTES)
                + (expiresAt == null ? 0 : Long.BYTES)
                + sizeOfString(lastSeq);

        ByteBuffer buffer = ByteBuffer.allocate(size);
        byte flags = 0;
        if (ttlSeconds != null) flags |= 0x01;
        if (configExpiresAt != null) flags |= 0x02;
        if (expiresAt != null) flags |= 0x04;
        if (lastSeq != null) flags |= 0x08;
        buffer.put(flags);

        writeString(buffer, streamId);
        writeString(buffer, contentType);
        writeString(buffer, nextOffset);

        if (ttlSeconds != null) buffer.putLong(ttlSeconds);
        if (configExpiresAt != null) buffer.putLong(configExpiresAt.toEpochMilli());
        if (expiresAt != null) buffer.putLong(expiresAt.toEpochMilli());
        if (lastSeq != null) writeString(buffer, lastSeq);

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

        String lastSeq = null;
        if ((flags & 0x08) != 0) {
            lastSeq = readString(buffer);
        }

        StreamConfig config = new StreamConfig(contentType, ttlSeconds, configExpiresAt);
        return new FileStreamMetadata(streamId, config, new Offset(nextOffset), expiresAt, lastSeq);
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
