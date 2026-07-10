package com.iskeru.computeadmin.auth.api;

import com.iskeru.computeadmin.auth.service.PersonalTokenService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Per-user MCP personal tokens. All operations scope to the current user: create
 * returns the plaintext once, list returns metadata only, delete revokes. A
 * not-owned id reads as 404.
 *
 * <p>spec-011.
 */
@Component
@Path("/tokens")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class TokenRS {

    private final PersonalTokenService tokenService;

    public TokenRS(PersonalTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @POST
    public AuthDtos.CreatedTokenView create(AuthDtos.TokenCreate body) {
        String label = body == null ? null : body.label();
        return AuthDtos.CreatedTokenView.of(tokenService.create(label));
    }

    @GET
    public List<AuthDtos.TokenView> list() {
        return tokenService.list().stream().map(AuthDtos.TokenView::of).toList();
    }

    @DELETE
    @Path("/{id}")
    public void revoke(@PathParam("id") String id) {
        tokenService.revoke(id);
    }
}
