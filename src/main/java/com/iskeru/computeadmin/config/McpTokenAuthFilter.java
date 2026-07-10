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
 * Authenticates {@code /mcp/*} with a per-user personal token (resolves S8). A
 * valid token binds an MCP {@link AuthContext} (touching {@code lastUsedAt});
 * anything else is rejected with 401 before the MCP servlet runs — an
 * unauthenticated agent reaches no tool.
 *
 * <p><strong>Bootstrap exception (spec 008).</strong> The self-setup tools
 * {@code begin_setup}/{@code complete_setup} are the only capability an
 * unauthenticated MCP session may reach; they expose no data. Those tools land in
 * the {@code mcp} module in spec 008, at which point this filter grows an explicit
 * allowance for the bootstrap exchange. Until they exist, every {@code /mcp}
 * request must carry a valid token.
 *
 * <p>Registered by {@link AuthFilterConfig}; not a {@code @Component} so Spring
 * Boot doesn't also map it to {@code /*}.
 *
 * <p>spec-011 (supersedes spec-002's {@code ActorScopeFilter}).
 */
public class McpTokenAuthFilter extends HttpFilter {

    private final PersonalTokenService tokenService;

    public McpTokenAuthFilter(PersonalTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Optional<AppUser> user = tokenService.authenticate(BearerTokens.from(request));
        if (user.isEmpty()) {
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
