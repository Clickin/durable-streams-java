package com.example.durable.streams.webmvc;

import io.durablestreams.server.core.CachePolicy;
import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.CursorPolicy;
import io.durablestreams.spring.webmvc.DurableStreamsWebMvcServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

@RestController
public class DurableStreamController {
    private final DurableStreamsHandler handler = DurableStreamsHandler.builder(new InMemoryStreamStore())
            .cursorPolicy(new CursorPolicy(Clock.systemUTC()))
            .cachePolicy(CachePolicy.defaultPrivate())
            .longPollTimeout(Duration.ofSeconds(25))
            .sseMaxDuration(Duration.ofSeconds(60))
            .build();
    private final DurableStreamsWebMvcServlet adapter = new DurableStreamsWebMvcServlet(handler);
    @RequestMapping("/**")
    public void handleDurableStream(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        adapter.service(request, response);
    }

}
