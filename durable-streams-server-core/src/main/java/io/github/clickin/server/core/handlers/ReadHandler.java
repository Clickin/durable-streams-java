package io.github.clickin.server.core.handlers;

import io.github.clickin.core.Protocol;
import io.github.clickin.server.core.HttpMethod;
import io.github.clickin.server.core.ProtocolEngine;
import io.github.clickin.server.core.QueryString;
import io.github.clickin.server.core.ResponseBody;
import io.github.clickin.server.core.ServerRequest;
import io.github.clickin.server.core.ServerResponse;

import java.util.Map;
import java.util.Objects;

public final class ReadHandler {
    private final ProtocolEngine engine;

    public ReadHandler(ProtocolEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public ServerResponse handle(ServerRequest request) {
        if (request.method() != HttpMethod.GET) {
            return new ServerResponse(405, new ResponseBody.Empty()).header("Cache-Control", "no-store");
        }
        Map<String, String> q = QueryString.parse(request.uri());
        String live = q.get(Protocol.Q_LIVE);
        if (live != null && !live.isEmpty()) {
            return new ServerResponse(400, new ResponseBody.Empty()).header("Cache-Control", "no-store");
        }
        return engine.handleRead(request);
    }
}
