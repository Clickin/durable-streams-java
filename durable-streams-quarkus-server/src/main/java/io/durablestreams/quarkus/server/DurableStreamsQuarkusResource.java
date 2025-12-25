package io.durablestreams.quarkus.server;

import io.durablestreams.server.core.*;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.*;

/**
 * Quarkus REST resource adapter over {@link DurableStreamsHandler}.
 *
 * <p>Mount under {@code /streams} by default.
 */
@Path("/streams")
@ApplicationScoped
public final class DurableStreamsQuarkusResource {

    @Inject DurableStreamsHandler engine;
    @Inject Sse sse;

    @PUT
    @Path("/{path:.*}")
    public Response put(@Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return invoke(HttpMethod.PUT, uriInfo, headers, body);
    }

    @POST
    @Path("/{path:.*}")
    public Response post(@Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return invoke(HttpMethod.POST, uriInfo, headers, body);
    }

    @DELETE
    @Path("/{path:.*}")
    public Response delete(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return invoke(HttpMethod.DELETE, uriInfo, headers, null);
    }

    @HEAD
    @Path("/{path:.*}")
    public Response head(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return invoke(HttpMethod.HEAD, uriInfo, headers, null);
    }

    @GET
    @Path("/{path:.*}")
    @Produces(MediaType.WILDCARD)
    public Object get(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        io.durablestreams.server.core.ServerResponse r = engine.handle(toEngineRequest(HttpMethod.GET, uriInfo, headers, null));
        if (r.body() instanceof ResponseBody.Sse sseBody) {
            // SSE: map to Multi<OutboundSseEvent>
            java.util.concurrent.Flow.Publisher<SseFrame> pub = sseBody.publisher();
            org.reactivestreams.Publisher<SseFrame> rs = org.reactivestreams.FlowAdapters.toPublisher(pub);

            return Multi.createFrom().publisher(rs)
                    .map(frame -> sse.newEventBuilder()
                            .name(frame.event())
                            .data(String.class, frame.data())
                            .build()
                    );
        }
        return toJaxRsResponse(r);
    }

    private Response invoke(HttpMethod method, UriInfo uriInfo, HttpHeaders headers, byte[] body) {
        io.durablestreams.server.core.ServerResponse r = engine.handle(toEngineRequest(method, uriInfo, headers, body));
        return toJaxRsResponse(r);
    }

    private static io.durablestreams.server.core.ServerRequest toEngineRequest(HttpMethod method, UriInfo uriInfo, HttpHeaders headers, byte[] body) {
        URI uri = uriInfo.getRequestUri();

        Map<String, List<String>> hdrs = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : headers.getRequestHeaders().entrySet()) {
            hdrs.put(e.getKey(), e.getValue());
        }

        ByteArrayInputStream in = (body == null || body.length == 0) ? null : new ByteArrayInputStream(body);
        return new io.durablestreams.server.core.ServerRequest(method, uri, hdrs, in);
    }

    private static Response toJaxRsResponse(io.durablestreams.server.core.ServerResponse r) {
        Response.ResponseBuilder b = Response.status(r.status());
        r.headers().forEach((k, vals) -> vals.forEach(v -> b.header(k, v)));

        ResponseBody body = r.body();
        if (body instanceof ResponseBody.Empty) return b.build();
        if (body instanceof ResponseBody.Bytes bb) return b.entity(bb.bytes()).build();

        // SSE is handled in GET
        return b.build();
    }
}
