package com.iskeru.computeadmin.ssh.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.ssh.KeyService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

/**
 * Exposes the app's public SSH key so a user can install it in a target's
 * {@code authorized_keys}. {@code @Secured}: an authenticated UI session reads it
 * (spec 008 also surfaces it as an MCP resource).
 *
 * <p>spec-003.
 */
@Component
@Path("/ssh")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class SshRS {

    private final KeyService keyService;

    public SshRS(KeyService keyService) {
        this.keyService = keyService;
    }

    @GET
    @Path("/public-key")
    public SshDtos.PublicKey publicKey() {
        return new SshDtos.PublicKey(keyService.publicKeyOpenSsh(), keyService.fingerprint());
    }
}
