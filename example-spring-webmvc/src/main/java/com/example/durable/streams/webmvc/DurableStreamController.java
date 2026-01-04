package com.example.durable.streams.webmvc;

import io.github.clickin.server.core.CachePolicy;
import io.github.clickin.server.core.DurableStreamsHandler;
import io.github.clickin.server.core.InMemoryStreamStore;
import io.github.clickin.server.spi.CursorPolicy;
import io.github.clickin.servlet.DurableStreamsServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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
    private final DurableStreamsServlet adapter = new DurableStreamsServlet(handler);
    @RequestMapping("/**")
    public void handleDurableStream(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        adapter.service(request, response);
    }

}
