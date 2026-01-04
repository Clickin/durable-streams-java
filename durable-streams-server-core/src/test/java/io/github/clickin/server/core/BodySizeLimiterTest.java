package io.github.clickin.server.core;

import io.github.clickin.server.spi.BodySizeLimiter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BodySizeLimiterTest {

    @Test
    void limitAllowsExactBytes() throws Exception {
        InputStream limited = BodySizeLimiter.limit(new ByteArrayInputStream("hello".getBytes()), 5);
        byte[] bytes = readAll(limited);
        assertThat(bytes).hasSize(5);
    }

    @Test
    void limitThrowsWhenExceeded() {
        InputStream limited = BodySizeLimiter.limit(new ByteArrayInputStream("hello!".getBytes()), 5);
        assertThatThrownBy(() -> readAll(limited))
                .isInstanceOf(BodySizeLimiter.PayloadTooLargeException.class);
    }

    @Test
    void limitReturnsNullForNullInput() {
        assertThat(BodySizeLimiter.limit(null, 5)).isNull();
    }

    @Test
    void limitIgnoresNonPositiveMax() throws Exception {
        InputStream limited = BodySizeLimiter.limit(new ByteArrayInputStream("hello".getBytes()), 0);
        byte[] bytes = readAll(limited);
        assertThat(bytes).hasSize(5);
    }

    @Test
    void limitIgnoresLongMaxValue() throws Exception {
        InputStream limited = BodySizeLimiter.limit(new ByteArrayInputStream("hello".getBytes()), Long.MAX_VALUE);
        byte[] bytes = readAll(limited);
        assertThat(bytes).hasSize(5);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int r;
        while ((r = in.read(buf)) >= 0) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }
}
