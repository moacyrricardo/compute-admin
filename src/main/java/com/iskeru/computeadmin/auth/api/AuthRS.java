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
 * Public sign-in resource. {@code POST /api/auth/register} self-registers an
 * email+password user and {@code POST /api/auth/login} authenticates one; both
 * return the app JWT plus the user view. Deliberately <strong>not</strong>
 * {@code @Secured} — this is the entry point that mints the session.
 *
 * <p>spec-011; email+password since spec-014.
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
    @Path("/register")
    public AuthDtos.Session register(AuthDtos.RegisterRequest body) {
        if (body == null) {
            throw new BadRequestException("request body is required");
        }
        AuthService.AuthResult result = authService.register(body.email(), body.password(), body.name());
        return AuthDtos.Session.of(result.token(), result.user());
    }

    @POST
    @Path("/login")
    public AuthDtos.Session login(AuthDtos.LoginRequest body) {
        if (body == null) {
            throw new BadRequestException("request body is required");
        }
        AuthService.AuthResult result = authService.login(body.email(), body.password());
        return AuthDtos.Session.of(result.token(), result.user());
    }
}
