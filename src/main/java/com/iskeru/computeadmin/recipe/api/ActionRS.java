package com.iskeru.computeadmin.recipe.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.CustomActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.EditActionInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

/**
 * Action authoring and the approval state machine over REST. This resource is the
 * <strong>only</strong> path to {@link ApprovalService}: because it is {@code
 * @Secured} it runs as {@code via = UI}, and the {@code mcp} module never
 * references {@link ApprovalService} (asserted by {@code GateArchTest}), so an
 * agent has no way to approve — approval is UI-only, the core invariant.
 *
 * <p>Every operation scopes to the current user; a not-owned id is a 404.
 *
 * <p>spec-004.
 */
@Component
@Path("/actions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class ActionRS {

    private final ActionService actionService;
    private final ApprovalService approvalService;

    public ActionRS(ActionService actionService, ApprovalService approvalService) {
        this.actionService = actionService;
        this.approvalService = approvalService;
    }

    @POST
    public RecipeDtos.ActionView add(RecipeDtos.AddActionRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        AddActionInput input = new AddActionInput(body.recipeId(), body.name(), body.description(),
                body.sudo(), body.argTokens(), body.paramDefs());
        return RecipeDtos.ActionView.of(actionService.addAction(input));
    }

    @POST
    @Path("/custom")
    public RecipeDtos.ActionView addCustom(RecipeDtos.AddCustomActionRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        CustomActionInput input = new CustomActionInput(body.machineId(), body.recipeId(),
                body.recipeName(), body.actionName(), body.scriptPath(), body.paramDefs(), body.sudo());
        return RecipeDtos.ActionView.of(actionService.addCustomAction(input));
    }

    @PUT
    @Path("/{id}")
    public RecipeDtos.ActionView edit(@PathParam("id") String id, RecipeDtos.EditActionRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        EditActionInput input = new EditActionInput(body.name(), body.description(),
                body.sudo(), body.argTokens(), body.paramDefs());
        return RecipeDtos.ActionView.of(actionService.editAction(id, input));
    }

    @POST
    @Path("/{id}/submit")
    public RecipeDtos.ActionView submit(@PathParam("id") String id) {
        return RecipeDtos.ActionView.of(approvalService.submitForApproval(id));
    }

    @POST
    @Path("/{id}/approve")
    public RecipeDtos.ActionView approve(@PathParam("id") String id) {
        return RecipeDtos.ActionView.of(approvalService.approve(id));
    }

    @POST
    @Path("/{id}/revoke")
    public RecipeDtos.ActionView revoke(@PathParam("id") String id) {
        return RecipeDtos.ActionView.of(approvalService.revoke(id));
    }
}
