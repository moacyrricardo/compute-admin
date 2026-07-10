package com.iskeru.computeadmin.config;

import com.iskeru.computeadmin.auth.service.JwtService;
import com.iskeru.computeadmin.auth.service.PersonalTokenService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the two auth filters against their surfaces at the front of the
 * chain: {@link JwtScopeFilter} on {@code /api/*} and {@link McpTokenAuthFilter}
 * on the MCP path. Declared as plain beans (not component-scanned filters)
 * precisely so neither also picks up Spring Boot's default {@code /*} mapping.
 * Async support is on because the MCP SSE endpoint calls {@code startAsync()}.
 *
 * <p>spec-011 (supersedes spec-002's {@code ActorScopeFilterConfig}).
 */
@Configuration
public class AuthFilterConfig {

    @Bean
    public FilterRegistrationBean<JwtScopeFilter> jwtScopeFilter(JwtService jwtService) {
        FilterRegistrationBean<JwtScopeFilter> registration =
                new FilterRegistrationBean<>(new JwtScopeFilter(jwtService));
        registration.setName("jwtScopeFilter");
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<McpTokenAuthFilter> mcpTokenAuthFilter(PersonalTokenService tokenService) {
        FilterRegistrationBean<McpTokenAuthFilter> registration =
                new FilterRegistrationBean<>(new McpTokenAuthFilter(tokenService));
        registration.setName("mcpTokenAuthFilter");
        registration.addUrlPatterns(McpServletConfig.MCP_PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(true);
        return registration;
    }
}
