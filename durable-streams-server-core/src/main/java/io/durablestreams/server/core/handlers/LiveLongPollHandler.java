package io.durablestreams.server.core.handlers;

import io.durablestreams.core.Protocol;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.ProtocolEngine;
import io.durablestreams.server.core.QueryString;
import io.durablestreams.server.core.ResponseBody;
import io.durablestreams.server.core.ServerRequest;
import io.durablestreams.server.core.ServerResponse;

import java.util.Map;
import java.util.Objects;

public final class LiveLongPollHandler {
    private final ProtocolEngine engine;

    public LiveLongPollHandler(ProtocolEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public ServerResponse handle(ServerRequest request) {
        if (request.method() != HttpMethod.GET) {
            return new ServerResponse(405, new ResponseBody.Empty()).header("Cache-Control", "no-store");
        }
        Map<String, String> q = QueryString.parse(request.uri());
        String live = q.get(Protocol.Q_LIVE);
        if (!Protocol.LIVE_LONG_POLL.equals(live)) {
            return new ServerResponse(400, new ResponseBody.Empty()).header("Cache-Control", "no-store");
        }
        return engine.handleRead(request);
    }
}
