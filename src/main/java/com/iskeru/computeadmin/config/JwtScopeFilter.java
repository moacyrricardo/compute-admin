package com.iskeru.computeadmin.config;

import com.iskeru.computeadmin.auth.service.JwtService;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.UnauthorizedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Binds a UI {@link AuthContext} on {@code /api/*} when a valid app JWT is
 * present. A missing or invalid token leaves the scope <strong>unbound</strong>
 * and the request proceeds — public resources ({@code POST /api/auth/google},
 * {@code GET /api/health}) still work, while {@code @Secured} resources are
 * refused 401 by {@code AuthFilter} on the same thread. This is the one place the
 * app JWT is validated.
 *
 * <p>Registered by {@link AuthFilterConfig}; deliberately not a {@code @Component}
 * so Spring Boot doesn't also map it to {@code /*}.
 *
 * <p>spec-011 (supersedes spec-002's {@code ActorScopeFilter}).
 */
public class JwtScopeFilter extends HttpFilter {

    private final JwtService jwtService;

    public JwtScopeFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        AuthContext context = resolve(request);
        if (context == null) {
            chain.doFilter(request, response);
            return;
        }
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

    /** The UI context proven by the request's JWT, or {@code null} if none/invalid. */
    private AuthContext resolve(HttpServletRequest request) {
        String jwt = BearerTokens.from(request);
        if (jwt == null) {
            return null;
        }
        try {
            JwtService.Claim claim = jwtService.verify(jwt);
            return AuthContext.ui(claim.userId(), claim.email());
        } catch (UnauthorizedException e) {
            return null;
        }
    }
}
