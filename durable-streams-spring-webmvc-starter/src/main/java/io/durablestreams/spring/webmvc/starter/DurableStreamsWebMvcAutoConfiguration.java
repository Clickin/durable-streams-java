package io.durablestreams.spring.webmvc.starter;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.StreamStore;
import io.durablestreams.spring.webmvc.DurableStreamsWebMvcServlet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(DurableStreamsWebMvcAutoConfiguration.DurableStreamsWebMvcProperties.class)
@ConditionalOnClass({DurableStreamsHandler.class, ServletRegistrationBean.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DurableStreamsWebMvcAutoConfiguration {

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
    DurableStreamsWebMvcServlet durableStreamsWebMvcServlet(DurableStreamsHandler handler) {
        return new DurableStreamsWebMvcServlet(handler);
    }

    @Bean
    @ConditionalOnProperty(prefix = "durable-streams.webmvc", name = "enabled", havingValue = "true", matchIfMissing = true)
    ServletRegistrationBean<DurableStreamsWebMvcServlet> durableStreamsServlet(
            DurableStreamsWebMvcServlet servlet,
            DurableStreamsWebMvcProperties properties
    ) {
        String mapping = toServletMapping(normalizeBasePath(properties.getBasePath()));
        return new ServletRegistrationBean<>(servlet, mapping);
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

    private static String toServletMapping(String basePath) {
        if ("/".equals(basePath)) {
            return "/*";
        }
        return basePath + "/*";
    }

    @ConfigurationProperties(prefix = "durable-streams.webmvc")
    public static class DurableStreamsWebMvcProperties {
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
