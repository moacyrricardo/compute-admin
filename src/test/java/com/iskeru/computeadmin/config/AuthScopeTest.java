package com.iskeru.computeadmin.config;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.service.JwtService;
import com.iskeru.computeadmin.auth.service.PersonalTokenService;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.UnauthorizedException;
import com.iskeru.computeadmin.common.Via;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tests the identity scope seam: {@link CurrentUser#require()} fails when
 * unbound, {@link JwtScopeFilter} binds a UI context only for a valid JWT, and
 * {@link McpTokenAuthFilter} binds an MCP context for a valid token and rejects
 * an unauthenticated request with 401.
 *
 * <p>spec-011 (supersedes spec-002's {@code ActorScopeTest}).
 */
class AuthScopeTest {

    private static final String SECRET = "unit-test-secret-abcdefghijklmnopqrstuvwxyz";
    private final JwtService jwtService = new JwtService(SECRET, 24);

    @Test
    void require_WhenUnbound_ThrowsUnauthorized() {
        assertThatThrownBy(CurrentUser::require).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void optional_WhenUnbound_IsEmpty() {
        assertThat(CurrentUser.optional()).isEmpty();
    }

    @Test
    void jwtFilter_WithValidJwt_BindsUiContext() throws Exception {
        AppUser user = user("u-1", "ada@example.com");
        String jwt = jwtService.mint(user);

        AuthContext bound = runJwtFilter("Bearer " + jwt);

        assertThat(bound).isNotNull();
        assertThat(bound.via()).isEqualTo(Via.UI);
        assertThat(bound.userId()).isEqualTo("u-1");
        assertThat(bound.email()).isEqualTo("ada@example.com");
    }

    @Test
    void jwtFilter_WithoutJwt_LeavesUnbound() throws Exception {
        assertThat(runJwtFilter(null)).isNull();
    }

    @Test
    void jwtFilter_WithInvalidJwt_LeavesUnbound() throws Exception {
        assertThat(runJwtFilter("Bearer not-a-jwt")).isNull();
    }

    @Test
    void mcpFilter_WithValidToken_BindsMcpContext() throws Exception {
        PersonalTokenService tokenService = tokenServiceReturning(user("u-2", "grace@example.com"));
        McpTokenAuthFilter filter = new McpTokenAuthFilter(tokenService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<AuthContext> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(CurrentUser.require());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(observed.get().via()).isEqualTo(Via.MCP);
        assertThat(observed.get().userId()).isEqualTo("u-2");
    }

    @Test
    void mcpFilter_WithoutToken_Rejects401() throws Exception {
        PersonalTokenService tokenService = tokenServiceReturning(null);
        McpTokenAuthFilter filter = new McpTokenAuthFilter(tokenService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled.get()).isFalse();
    }

    private AuthContext runJwtFilter(String authHeader) throws Exception {
        JwtScopeFilter filter = new JwtScopeFilter(jwtService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/tokens");
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<AuthContext> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(CurrentUser.optional().orElse(null));
        filter.doFilter(request, response, chain);
        return observed.get();
    }

    private static AppUser user(String id, String email) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setEmail(email);
        user.setGoogleSub("dev|" + email);
        return user;
    }

    /** A {@link PersonalTokenService} whose {@code authenticate} returns {@code user} (or empty). */
    private static PersonalTokenService tokenServiceReturning(AppUser user) {
        return new PersonalTokenService(null, null) {
            @Override
            public Optional<AppUser> authenticate(String plaintext) {
                return Optional.ofNullable(user);
            }
        };
    }
}
