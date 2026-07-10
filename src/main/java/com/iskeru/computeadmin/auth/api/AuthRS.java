package com.iskeru.computeadmin.auth.api;

import com.iskeru.computeadmin.auth.service.AuthService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

/**
 * Public sign-in resource. {@code POST /api/auth/google} exchanges a Google
 * credential for an app JWT and the user view. Deliberately <strong>not</strong>
 * {@code @Secured} — this is the entry point that mints the session.
 *
 * <p>spec-011.
 */
@Component
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthRS {

    private final AuthService authService;

    public AuthRS(AuthService authService) {
        this.authService = authService;
    }

    @POST
    @Path("/google")
    public AuthDtos.Session google(AuthDtos.GoogleLogin body) {
        if (body == null || body.credential() == null || body.credential().isBlank()) {
            throw new BadRequestException("credential is required");
        }
        AuthService.AuthResult result = authService.loginWithGoogle(body.credential());
        return AuthDtos.Session.of(result.token(), result.user());
    }
}
