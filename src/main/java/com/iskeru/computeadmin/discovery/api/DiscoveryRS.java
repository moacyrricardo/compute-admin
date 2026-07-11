package com.iskeru.computeadmin.discovery.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

/**
 * Recipe discovery over REST: {@code POST /api/machines/{id}/discover} runs the
 * discoverers against one of the current user's machines and returns the proposals
 * it persisted (all {@code PENDING_APPROVAL}). {@code @Secured}, and the machine
 * must belong to the caller — a not-owned or absent id is a 404 (resolved in
 * {@link DiscoveryService}; existence never leaked).
 *
 * <p>The MCP {@code discover_recipes} tool (spec 008) calls the same service as the
 * token's user; there is no approve path here, so discovery cannot bypass the gate.
 *
 * <p>spec-006.
 */
@Component
@Path("/machines")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class DiscoveryRS {

    private final DiscoveryService discoveryService;

    public DiscoveryRS(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @POST
    @Path("/{id}/discover")
    public DiscoveryDtos.DiscoveryResult discover(@PathParam("id") String id) {
        return DiscoveryDtos.DiscoveryResult.of(id, discoveryService.discover(id));
    }
}
