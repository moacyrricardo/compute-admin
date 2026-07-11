package com.iskeru.computeadmin.recipe.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.RecipeService.CreateRecipeInput;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Per-user recipe registry over REST. Creating recipes is open here and on MCP;
 * the approval gate ({@code ActionRS}) is what MCP cannot reach. Every operation
 * scopes to the current user — a not-owned id is a 404.
 *
 * <p>spec-004.
 */
@Component
@Path("/recipes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class RecipeRS {

    private final RecipeService recipeService;

    public RecipeRS(RecipeService recipeService) {
        this.recipeService = recipeService;
    }

    @POST
    public RecipeDtos.RecipeView create(RecipeDtos.RecipeRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        CreateRecipeInput input = new CreateRecipeInput(
                body.machineId(), body.name(), body.description(), body.type());
        return RecipeDtos.RecipeView.of(recipeService.create(input));
    }

    @GET
    public List<RecipeDtos.RecipeView> list(@QueryParam("machineId") String machineId) {
        if (machineId == null || machineId.isBlank()) {
            throw new BadRequestException("machineId is required");
        }
        return recipeService.listForMachine(machineId).stream().map(RecipeDtos.RecipeView::of).toList();
    }

    @GET
    @Path("/{id}/actions")
    public List<RecipeDtos.ActionView> listActions(@PathParam("id") String id) {
        return recipeService.listActions(id).stream().map(RecipeDtos.ActionView::of).toList();
    }
}
