package io.github.clickin.server.spi;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Utility for enforcing maximum request body size.
 *
 * <p>Per the protocol specification, servers should return 413 Payload Too Large
 * when request bodies exceed configured limits.
 */
public final class BodySizeLimiter {

    private BodySizeLimiter() {}

    /**
     * Wraps an input stream to enforce a maximum byte limit.
     *
     * @param delegate the underlying input stream
     * @param maxBytes maximum number of bytes allowed
     * @return a wrapped stream that throws {@link PayloadTooLargeException} if limit is exceeded
     */
    public static InputStream limit(InputStream delegate, long maxBytes) {
        if (delegate == null) return null;
        if (maxBytes <= 0) return delegate;
        if (maxBytes == Long.MAX_VALUE) return delegate;
        return new LimitedInputStream(delegate, maxBytes);
    }

    /**
     * Exception thrown when payload size exceeds the configured limit.
     */
    public static final class PayloadTooLargeException extends IOException {
        private final long maxBytes;

        public PayloadTooLargeException(long maxBytes) {
            super("Payload exceeds maximum size of " + maxBytes + " bytes");
            this.maxBytes = maxBytes;
        }

        public long maxBytes() {
            return maxBytes;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead = 0;

        LimitedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            int b = super.read();
            if (b >= 0) {
                bytesRead++;
                if (bytesRead > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (bytesRead > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            long remaining = maxBytes - bytesRead;
            int toRead = (int) Math.min(len, remaining + 1);
            int n = super.read(b, off, toRead);
            if (n > 0) {
                bytesRead += n;
                if (bytesRead > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long remaining = maxBytes - bytesRead;
            long toSkip = Math.min(n, remaining + 1);
            long skipped = super.skip(toSkip);
            bytesRead += skipped;
            if (bytesRead > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            return skipped;
        }

    }
}
