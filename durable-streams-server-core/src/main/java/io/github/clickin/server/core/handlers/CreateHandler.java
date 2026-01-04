package io.github.clickin.server.core.handlers;

import io.github.clickin.server.core.HttpMethod;
import io.github.clickin.server.core.ProtocolEngine;
import io.github.clickin.server.core.ResponseBody;
import io.github.clickin.server.core.ServerRequest;
import io.github.clickin.server.core.ServerResponse;

import java.util.Objects;

public final class CreateHandler {
    private final ProtocolEngine engine;

    public CreateHandler(ProtocolEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public ServerResponse handle(ServerRequest request) {
        if (request.method() != HttpMethod.PUT) {
            return new ServerResponse(405, new ResponseBody.Empty()).header("Cache-Control", "no-store");
        }
        return engine.handleCreate(request);
    }
}
