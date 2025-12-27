package io.durablestreams.spring.webmvc.starter;

import io.durablestreams.server.core.DurableStreamsHandler;
import io.durablestreams.server.core.InMemoryStreamStore;
import io.durablestreams.server.spi.StreamStore;
import io.durablestreams.spring.webmvc.DurableStreamsWebMvcServlet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Durable Streams with Spring WebMVC.
 *
 * <p>Provides default beans for {@link StreamStore}, {@link DurableStreamsHandler},
 * and {@link DurableStreamsWebMvcServlet}. These can be overridden by defining your own beans.
 *
 * <p><strong>Note:</strong> This autoconfiguration does NOT register any servlets.
 * Per the Durable Streams protocol, "a stream is simply a URL" and servers have complete
 * freedom to organize streams using any URL scheme. You must manually configure your
 * servlet mappings to use the handler.
 *
 * <p>Example manual servlet registration:
 * <pre>{@code
 * @Configuration
 * public class StreamServletConfig {
 *
 *     @Bean
 *     public ServletRegistrationBean<DurableStreamsWebMvcServlet> streamServlet(
 *             DurableStreamsWebMvcServlet servlet) {
 *         return new ServletRegistrationBean<>(servlet, "/api/streams/*");
 *     }
 *
 *     // Multiple servlets example
 *     @Bean
 *     public ServletRegistrationBean<DurableStreamsWebMvcServlet> userStreamsServlet(
 *             @Qualifier("userStore") StreamStore userStore) {
 *         DurableStreamsHandler handler = new DurableStreamsHandler(userStore);
 *         DurableStreamsWebMvcServlet servlet = new DurableStreamsWebMvcServlet(handler);
 *         return new ServletRegistrationBean<>(servlet, "/users/*/events/*");
 *     }
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass({DurableStreamsHandler.class, DurableStreamsWebMvcServlet.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DurableStreamsWebMvcAutoConfiguration {

    /**
     * Provides a default in-memory {@link StreamStore}.
     * Override by defining your own StreamStore bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public StreamStore durableStreamsStore() {
        return new InMemoryStreamStore();
    }

    /**
     * Provides a default {@link DurableStreamsHandler} using the configured store.
     * Override by defining your own DurableStreamsHandler bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public DurableStreamsHandler durableStreamsHandler(StreamStore store) {
        return new DurableStreamsHandler(store);
    }

    /**
     * Provides a default {@link DurableStreamsWebMvcServlet} for servlet-based integration.
     * Override by defining your own servlet bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public DurableStreamsWebMvcServlet durableStreamsWebMvcServlet(DurableStreamsHandler handler) {
        return new DurableStreamsWebMvcServlet(handler);
    }
}
