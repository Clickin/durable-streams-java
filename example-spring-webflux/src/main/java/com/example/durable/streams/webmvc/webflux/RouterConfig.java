package com.example.durable.streams.webmvc.webflux;

import io.github.clickin.server.core.CachePolicy;
import io.github.clickin.server.core.DurableStreamsHandler;
import io.github.clickin.server.core.InMemoryStreamStore;
import io.github.clickin.server.spi.CursorPolicy;
import io.github.clickin.spring.webflux.DurableStreamsWebFluxAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class RouterConfig {
    @Bean
    public DurableStreamsHandler durableStreamsHandler() {
        return DurableStreamsHandler.builder(new InMemoryStreamStore())
                .cursorPolicy(new CursorPolicy(Clock.systemUTC()))
                .cachePolicy(CachePolicy.defaultPrivate())
                .longPollTimeout(Duration.ofSeconds(25))
                .sseMaxDuration(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public DurableStreamsWebFluxAdapter durableStreamsWebFluxAdapter(DurableStreamsHandler handler) {
        return new DurableStreamsWebFluxAdapter(handler);
    }

    @Bean
    public RouterFunction<ServerResponse> durableStreamsRoutes(DurableStreamsWebFluxAdapter adapter) {
        return RouterFunctions.route()
                .add(RouterFunctions.route(req -> true, adapter::handle))
                .build();
    }
}

