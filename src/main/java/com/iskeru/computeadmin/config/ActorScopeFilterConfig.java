package com.iskeru.computeadmin.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link ActorScopeFilter} against exactly the two authenticated
 * surfaces ({@code /api/*} and the MCP path) at the front of the chain, so the
 * ambient actor is bound before any downstream filter or servlet runs. Declared
 * as a plain bean (not by component-scanning the filter) precisely so the filter
 * does not also pick up Spring Boot's default {@code /*} mapping.
 *
 * <p>Async support is enabled because the MCP SSE endpoint calls
 * {@code startAsync()}; a non-async filter in the chain would break it.
 *
 * <p>spec-002.
 */
@Configuration
public class ActorScopeFilterConfig {

    @Bean
    public FilterRegistrationBean<ActorScopeFilter> actorScopeFilter() {
        FilterRegistrationBean<ActorScopeFilter> registration =
                new FilterRegistrationBean<>(new ActorScopeFilter());
        registration.setName("actorScopeFilter");
        registration.addUrlPatterns("/api/*", McpServletConfig.MCP_PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(true);
        return registration;
    }
}
