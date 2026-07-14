package com.iskeru.computeadmin.discovery.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.discovery.api.DiscoveryEnablementDtos.DiscoveryStateView;
import com.iskeru.computeadmin.discovery.api.DiscoveryEnablementDtos.FamilyView;
import com.iskeru.computeadmin.discovery.api.DiscoveryEnablementDtos.ProposalView;
import com.iskeru.computeadmin.discovery.api.DiscoveryEnablementDtos.SetEnablementRequest;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.discovery.service.DiscoveryEnablementService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Recipe discovery over REST: {@code POST /api/machines/{id}/discover} runs the enabled
 * discoverers against one of the current user's machines and returns the proposals it
 * persisted (all {@code PENDING_APPROVAL}); {@code GET /api/machines/{id}/discovery}
 * returns the per-family enablement toggles + the last proposals; and
 * {@code PUT /api/machines/{id}/discovery/{family}} enables/disables one family (spec-035).
 * {@code @Secured}, and the machine must belong to the caller — a not-owned or absent id
 * is a 404 (resolved in the services; existence never leaked).
 *
 * <p>Enablement is a human/operator decision, so it is deliberately <strong>UI-only</strong>:
 * there is no MCP surface for it (cf. spec-028/S9). And enablement is <strong>not</strong>
 * the approval gate — enabling a family only lets it probe and propose; there is no approve
 * path here, so discovery still cannot bypass the gate.
 *
 * <p>spec-006; per-family enablement in spec-035.
 */
@Component
@Path("/machines")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class DiscoveryRS {

    private final DiscoveryService discoveryService;
    private final DiscoveryEnablementService enablementService;
    private final RecipeService recipeService;

    public DiscoveryRS(DiscoveryService discoveryService,
                       DiscoveryEnablementService enablementService, RecipeService recipeService) {
        this.discoveryService = discoveryService;
        this.enablementService = enablementService;
        this.recipeService = recipeService;
    }

    @POST
    @Path("/{id}/discover")
    public DiscoveryDtos.DiscoveryResult discover(@PathParam("id") String id) {
        return DiscoveryDtos.DiscoveryResult.of(id, discoveryService.discover(id));
    }

    @GET
    @Path("/{id}/discovery")
    public DiscoveryStateView discoveryState(@PathParam("id") String id) {
        return state(id);
    }

    @PUT
    @Path("/{id}/discovery/{family}")
    public DiscoveryStateView setEnablement(@PathParam("id") String id,
                                            @PathParam("family") String family,
                                            SetEnablementRequest body) {
        if (body == null || body.enabled() == null) {
            throw new BadRequestException("enabled is required");
        }
        enablementService.setEnabled(id, parseFamily(family), body.enabled());
        return state(id);
    }

    /** The full discovery panel state (families + last proposals) for a machine. */
    private DiscoveryStateView state(String id) {
        List<FamilyView> families = enablementService.familyStates(id).stream()
                .map(FamilyView::of).toList();
        List<ProposalView> proposals = recipeService.listForMachine(id).stream()
                .map(this::toProposal).toList();
        return new DiscoveryStateView(id, families, proposals);
    }

    private ProposalView toProposal(Recipe recipe) {
        return ProposalView.of(recipe, recipeService.listActions(recipe.getId()));
    }

    /** Parses the {@code {family}} path segment (case-insensitive) to a {@link DiscovererFamily}. */
    private static DiscovererFamily parseFamily(String family) {
        if (family == null || family.isBlank()) {
            throw new BadRequestException("family is required");
        }
        try {
            return DiscovererFamily.valueOf(family.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("unknown discoverer family: " + family);
        }
    }
}
