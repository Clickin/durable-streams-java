package io.durablestreams.spring.webflux.starter;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.StreamStore;
import io.durablestreams.spring.webflux.DurableStreamsWebFluxAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@AutoConfiguration
@EnableConfigurationProperties(DurableStreamsWebFluxAutoConfiguration.DurableStreamsWebFluxProperties.class)
@ConditionalOnClass({DurableStreamsHandler.class, RouterFunction.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class DurableStreamsWebFluxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    StreamStore durableStreamsStore() {
        return new InMemoryStreamStore();
    }

    @Bean
    @ConditionalOnMissingBean
    DurableStreamsHandler durableStreamsHandler(StreamStore store) {
        return new DurableStreamsHandler(store);
    }

    @Bean
    @ConditionalOnMissingBean
    DurableStreamsWebFluxAdapter durableStreamsWebFluxAdapter(DurableStreamsHandler handler) {
        return new DurableStreamsWebFluxAdapter(handler);
    }

    @Bean
    @ConditionalOnProperty(prefix = "durable-streams.webflux", name = "enabled", havingValue = "true", matchIfMissing = true)
    RouterFunction<ServerResponse> durableStreamsWebFluxRoutes(
            DurableStreamsWebFluxAdapter adapter,
            DurableStreamsWebFluxProperties properties
    ) {
        String basePath = normalizeBasePath(properties.getBasePath());
        String pattern = "/".equals(basePath) ? "/**" : basePath + "/**";
        return RouterFunctions.route(RequestPredicates.path(pattern), adapter::handle);
    }

    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "/streams";
        }
        String trimmed = basePath.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        if (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @ConfigurationProperties(prefix = "durable-streams.webflux")
    public static class DurableStreamsWebFluxProperties {
        private boolean enabled = true;
        private String basePath = "/streams";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }
}
