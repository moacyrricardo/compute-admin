package com.iskeru.computeadmin.config;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.service.PersonalTokenService;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates {@code /mcp/*} with a per-user personal token (resolves S8) and
 * binds the caller so it reaches tool handlers (spec-008).
 *
 * <p><strong>Caller propagation into tools (spec-008).</strong> This filter binds
 * an MCP {@link AuthContext} on the request thread via
 * {@link CurrentUser#runWhere}. That binding reaches tool handlers because the MCP
 * server is built with <em>immediate execution</em> (see {@link McpServletConfig}):
 * the servlet-SSE transport handles a {@code /mcp/message} POST by blocking the
 * request thread on {@code session.handle(...).block()}, and immediate execution
 * runs the sync tool on that same thread rather than offloading it to a Reactor
 * pool — so the thread-confined {@code ScopedValue} bound here is in scope inside
 * the tool. (Session-keyed rebinding is not viable: the SDK's
 * {@code McpServerSession} id exposed to a tool differs from the transport session
 * id the filter sees, leaving no shared correlation key.)
 *
 * <p>Auth outcomes:
 * <ul>
 *   <li><strong>Valid token:</strong> binds an MCP {@link AuthContext} (touching
 *       {@code lastUsedAt}); every tool then scopes to that user.</li>
 *   <li><strong>Presented-but-invalid token:</strong> rejected 401 before the MCP
 *       servlet runs — a bad credential reaches no tool.</li>
 *   <li><strong>No token at all (bootstrap session):</strong> allowed through
 *       <em>unbound</em>. This is the spec-008 self-setup allowance: a not-yet-paired
 *       agent may initialize, list tools, and call only {@code begin_setup}/
 *       {@code complete_setup}. Every data/run tool refuses an unbound session in
 *       the {@link McpServletConfig} wrapper (no bound caller ⇒ unauthorized), so a
 *       tokenless session still reaches no user data (S8 preserved).</li>
 * </ul>
 *
 * <p>Registered by {@link AuthFilterConfig}; not a {@code @Component} so Spring
 * Boot doesn't also map it to {@code /*}.
 *
 * <p>spec-011 (supersedes spec-002's {@code ActorScopeFilter}); bootstrap allowance
 * and tool-thread caller propagation added in spec-008.
 */
public class McpTokenAuthFilter extends HttpFilter {

    private final PersonalTokenService tokenService;

    public McpTokenAuthFilter(PersonalTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String credential = BearerTokens.from(request);
        if (credential == null) {
            // Tokenless bootstrap session: proceed unbound. The tool wrapper lets
            // only begin_setup/complete_setup run without a bound caller.
            chain.doFilter(request, response);
            return;
        }
        Optional<AppUser> user = tokenService.authenticate(credential);
        if (user.isEmpty()) {
            // A credential was presented but is invalid/revoked — reject it.
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        AuthContext context = AuthContext.mcp(user.get().getId(), user.get().getEmail());
        try {
            CurrentUser.runWhere(context, () -> {
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
