package io.durablestreams.server.core.handlers;

import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ProtocolEngine;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.ServerRequest;
import io.durablestreams.server.core.ServerResponse;

import java.util.Objects;

public final class AppendHandler {
    private final ProtocolEngine engine;

    public AppendHandler(ProtocolEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public ServerResponse handle(ServerRequest request) {
        if (request.method() != HttpMethod.POST) {
            return new ServerResponse(405, new ResponseBody.Empty()).header("Cache-Control", "no-store");
        }
        return engine.handleAppend(request);
    }
}
