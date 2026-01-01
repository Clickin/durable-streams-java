package example.micronaut.durable;

import io.durablestreams.micronaut.DurableStreamsMicronautAdapter;
import io.durablestreams.server.core.CachePolicy;
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.CursorPolicy;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.time.Clock;
import java.time.Duration;

@ExecuteOn(TaskExecutors.BLOCKING)
@Produces(MediaType.ALL)
@Consumes(MediaType.ALL)
@Controller("/")
final class DurableStreamsController {
    private final DurableStreamsHandler handler = DurableStreamsHandler.builder(new InMemoryStreamStore())
        .cursorPolicy(new CursorPolicy(Clock.systemUTC()))
        .cachePolicy(CachePolicy.defaultPrivate())
        .longPollTimeout(Duration.ofSeconds(25))
        .sseMaxDuration(Duration.ofSeconds(60))
        .build();

    @Get("/{+path}")
    HttpResponse<?> get(@PathVariable("path") String path, HttpRequest<byte[]> request) {
        return handle(request);
    }

    @Post("/{+path}")
    HttpResponse<?> post(@PathVariable("path") String path, HttpRequest<byte[]> request) {
        return handle(request);
    }

    @Put("/{+path}")
    HttpResponse<?> put(@PathVariable("path") String path, HttpRequest<byte[]> request) {
        return handle(request);
    }

    @Delete("/{+path}")
    HttpResponse<?> delete(@PathVariable("path") String path, HttpRequest<byte[]> request) {
        return handle(request);
    }

    private HttpResponse<?> handle(HttpRequest<byte[]> request) {
        return DurableStreamsMicronautAdapter.handle(request, handler);
    }
}