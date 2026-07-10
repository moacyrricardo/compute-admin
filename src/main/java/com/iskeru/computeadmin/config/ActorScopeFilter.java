package com.iskeru.computeadmin.config;

import com.iskeru.computeadmin.common.Actor;
import com.iskeru.computeadmin.common.CurrentActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Binds the ambient {@link Actor} for the duration of a request: {@code MCP} for
 * the MCP servlet path, {@code UI} for {@code /api/*}. Everything downstream —
 * services, the run path, the Envers listener — reads it through
 * {@link CurrentActor}.
 *
 * <p>Registered (with its explicit URL patterns and async support) by
 * {@link ActorScopeFilterConfig}; deliberately <strong>not</strong> a
 * {@code @Component} so Spring Boot doesn't also map it to the default {@code /*}.
 * Mirrors birthday-rsvp's current-user scope filter.
 *
 * <p>spec-002.
 */
public class ActorScopeFilter extends HttpFilter {

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Actor actor = request.getRequestURI().startsWith(McpServletConfig.MCP_PATH + "/")
                ? Actor.MCP
                : Actor.UI;
        try {
            CurrentActor.runWhere(actor, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}
