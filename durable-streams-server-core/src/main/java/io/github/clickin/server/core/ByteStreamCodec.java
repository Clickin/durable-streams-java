package io.github.clickin.server.core;

import io.github.clickin.server.spi.StreamCodec;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Default codec for all non-JSON content types.
 *
 * <p>State is a simple byte buffer; offset semantics are byte positions.
 */
public final class ByteStreamCodec implements StreamCodec {

    public static final ByteStreamCodec INSTANCE = new ByteStreamCodec();

    private ByteStreamCodec() {}

    @Override
    public String contentType() {
        // Used as a fallback, not by exact matching.
        return "*/*";
    }

    @Override
    public State createEmpty() {
        return new StateImpl(new ByteArrayOutputStream());
    }

    @Override
    public void applyInitial(State state, InputStream body) throws Exception {
        Objects.requireNonNull(state, "state");
        if (body == null) return;
        byte[] b = readAllBytes(body);
        if (b.length == 0) return;
        ((StateImpl) state).buf.writeBytes(b);
    }

    @Override
    public void append(State state, InputStream body) throws Exception {
        Objects.requireNonNull(state, "state");
        if (body == null) throw new IllegalArgumentException("empty body");
        byte[] b = readAllBytes(body);
        if (b.length == 0) throw new IllegalArgumentException("empty body");
        ((StateImpl) state).buf.writeBytes(b);
    }

    @Override
    public ReadChunk read(State state, long start, int limit) {
        Objects.requireNonNull(state, "state");
        if (start < 0) start = 0;
        StateImpl s = (StateImpl) state;
        byte[] all = s.buf.toByteArray();
        int st = (int) Math.min((long) all.length, start);
        int en = (int) Math.min((long) all.length, (long) st + (long) Math.max(0, limit));
        byte[] slice = Arrays.copyOfRange(all, st, en);
        boolean upToDate = en >= all.length;
        return new ReadChunk(slice, en, upToDate);
    }

    @Override
    public long size(State state) {
        return ((StateImpl) state).buf.size();
    }

    private static final class StateImpl implements StreamCodec.State {
        private final ByteArrayOutputStream buf;

        private StateImpl(ByteArrayOutputStream buf) {
            this.buf = buf;
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
        return out.toByteArray();
    }
}
