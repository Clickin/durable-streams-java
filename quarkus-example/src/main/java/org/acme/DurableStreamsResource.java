package org.acme;

import io.durablestreams.quarkus.DurableStreamsQuarkusAdapter;
import io.durablestreams.server.core.CachePolicy;
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.HttpMethod;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.CursorPolicy;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.time.Clock;
import java.time.Duration;

@Path("/")
public class DurableStreamsResource {
    private final DurableStreamsHandler handler = DurableStreamsHandler.builder(new InMemoryStreamStore())
        .cursorPolicy(new CursorPolicy(Clock.systemUTC()))
        .cachePolicy(CachePolicy.defaultPrivate())
        .longPollTimeout(Duration.ofSeconds(25))
        .sseMaxDuration(Duration.ofSeconds(60))
        .build();

    @GET
    @Path("{path:.*}")
    public Response get(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return DurableStreamsQuarkusAdapter.handle(HttpMethod.GET, uriInfo, headers, null, handler);
    }

    @POST
    @Path("{path:.*}")
    public Response post(@Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return DurableStreamsQuarkusAdapter.handle(HttpMethod.POST, uriInfo, headers, body, handler);
    }

    @PUT
    @Path("{path:.*}")
    public Response put(@Context UriInfo uriInfo, @Context HttpHeaders headers, byte[] body) {
        return DurableStreamsQuarkusAdapter.handle(HttpMethod.PUT, uriInfo, headers, body, handler);
    }

    @DELETE
    @Path("{path:.*}")
    public Response delete(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return DurableStreamsQuarkusAdapter.handle(HttpMethod.DELETE, uriInfo, headers, null, handler);
    }

    @HEAD
    @Path("{path:.*}")
    public Response head(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return DurableStreamsQuarkusAdapter.handle(HttpMethod.HEAD, uriInfo, headers, null, handler);
    }
}
