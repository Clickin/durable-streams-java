package io.durablestreams.server.core;

import io.durablestreams.core.Offset;
import io.durablestreams.core.Protocol;
import io.durablestreams.server.spi.OffsetGenerator;

public final class LexiLongOffsetGenerator implements OffsetGenerator {
    @Override
    public Offset next(Offset previous, long size) {
        if (size < 0) throw new IllegalArgumentException("size must be >= 0");
        return new Offset(LexiLong.encode(size));
    }

    @Override
    public String encode(Offset offset) {
        if (offset == null) throw new IllegalArgumentException("offset");
        if (Protocol.OFFSET_BEGINNING.equals(offset.value())) return offset.value();
        return LexiLong.encode(LexiLong.decode(offset.value()));
    }

    @Override
    public Offset decode(String token) {
        if (token == null) throw new IllegalArgumentException("token");
        if (Protocol.OFFSET_BEGINNING.equals(token)) return Offset.beginning();
        return new Offset(token);
    }
}
