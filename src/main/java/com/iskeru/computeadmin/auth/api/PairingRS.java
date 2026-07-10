package com.iskeru.computeadmin.auth.api;

import com.iskeru.computeadmin.auth.service.PairingService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

/**
 * The UI side of MCP self-setup pairing. The {@code /setup} page reads a request
 * by user code and the signed-in human approves or denies it. Approval mints a
 * personal token bound to that human — so "auth/approval is UI-only" holds: the
 * client never authenticates itself.
 *
 * <p>spec-011.
 */
@Component
@Path("/mcp-pairing")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class PairingRS {

    private final PairingService pairingService;

    public PairingRS(PairingService pairingService) {
        this.pairingService = pairingService;
    }

    @GET
    @Path("/{userCode}")
    public AuthDtos.PairingView get(@PathParam("userCode") String userCode) {
        return AuthDtos.PairingView.of(pairingService.getByUserCode(userCode));
    }

    @POST
    @Path("/{userCode}/approve")
    public AuthDtos.PairingView approve(@PathParam("userCode") String userCode) {
        pairingService.approve(userCode);
        return AuthDtos.PairingView.of(pairingService.getByUserCode(userCode));
    }

    @POST
    @Path("/{userCode}/deny")
    public AuthDtos.PairingView deny(@PathParam("userCode") String userCode) {
        pairingService.deny(userCode);
        return AuthDtos.PairingView.of(pairingService.getByUserCode(userCode));
    }
}
