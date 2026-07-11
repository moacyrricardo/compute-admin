package com.iskeru.computeadmin.blueprint.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.blueprint.service.BlueprintService;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.AddBlueprintActionInput;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.CreateBlueprintInput;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.EditBlueprintActionInput;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.EditBlueprintInput;
import com.iskeru.computeadmin.blueprint.service.InstantiationService;
import com.iskeru.computeadmin.blueprint.service.InstantiationService.InstantiateInput;
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

/**
 * Per-user blueprint authoring and instantiation over REST. Authoring a blueprint
 * and instantiating it are open here and on MCP; crucially, neither approves —
 * instantiation produces {@code PENDING_APPROVAL} per-machine actions that must be
 * approved through the {@code recipe} UI gate ({@code ActionRS}), which the
 * {@code mcp} module cannot reach. Every operation scopes to the current user; a
 * not-owned blueprint or machine is a 404.
 *
 * <p>spec-010.
 */
@Component
@Path("/blueprints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class BlueprintRS {

    private final BlueprintService blueprintService;
    private final InstantiationService instantiationService;

    public BlueprintRS(BlueprintService blueprintService, InstantiationService instantiationService) {
        this.blueprintService = blueprintService;
        this.instantiationService = instantiationService;
    }

    @POST
    public BlueprintDtos.BlueprintView create(BlueprintDtos.BlueprintRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        return BlueprintDtos.BlueprintView.of(blueprintService.createBlueprint(
                new CreateBlueprintInput(body.name(), body.description(), body.type())));
    }

    @GET
    public List<BlueprintDtos.BlueprintView> list() {
        return blueprintService.list().stream().map(BlueprintDtos.BlueprintView::of).toList();
    }

    @GET
    @Path("/{id}")
    public BlueprintDtos.BlueprintView get(@PathParam("id") String id) {
        return BlueprintDtos.BlueprintView.of(blueprintService.requireBlueprint(id));
    }

    @PUT
    @Path("/{id}")
    public BlueprintDtos.BlueprintView edit(@PathParam("id") String id, BlueprintDtos.EditBlueprintRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        return BlueprintDtos.BlueprintView.of(blueprintService.editBlueprint(id,
                new EditBlueprintInput(body.name(), body.description(), body.type())));
    }

    @GET
    @Path("/{id}/actions")
    public List<BlueprintDtos.BlueprintActionView> listActions(@PathParam("id") String id) {
        return blueprintService.listActions(id).stream()
                .map(BlueprintDtos.BlueprintActionView::of).toList();
    }

    @POST
    @Path("/{id}/actions")
    public BlueprintDtos.BlueprintActionView addAction(@PathParam("id") String id,
                                                       BlueprintDtos.AddBlueprintActionRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        return BlueprintDtos.BlueprintActionView.of(blueprintService.addBlueprintAction(
                new AddBlueprintActionInput(id, body.name(), body.description(), body.sudo(),
                        body.argTokens(), body.paramDefs())));
    }

    @PUT
    @Path("/actions/{actionId}")
    public BlueprintDtos.BlueprintActionView editAction(@PathParam("actionId") String actionId,
                                                        BlueprintDtos.EditBlueprintActionRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        return BlueprintDtos.BlueprintActionView.of(blueprintService.editBlueprintAction(actionId,
                new EditBlueprintActionInput(body.name(), body.description(), body.sudo(),
                        body.argTokens(), body.paramDefs())));
    }

    @POST
    @Path("/{id}/instantiate")
    public List<BlueprintDtos.InstantiatedRecipeView> instantiate(@PathParam("id") String id,
                                                                  BlueprintDtos.InstantiateRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        return instantiationService.instantiate(id, new InstantiateInput(body.machineIds(), body.tag()))
                .stream().map(BlueprintDtos.InstantiatedRecipeView::of).toList();
    }
}
