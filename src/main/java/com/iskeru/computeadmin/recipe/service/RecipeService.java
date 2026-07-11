package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.repository.ActionRepository;
import com.iskeru.computeadmin.recipe.repository.RecipeRepository;
import jakarta.ws.rs.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Recipes, scoped per user. Ownership derives through {@code machine.owner}
 * (spec 011): a recipe belongs to whoever owns its machine. Machine ownership is
 * resolved through {@link MachineService} (service→service, never the machine
 * repository); a not-owned or absent id is a 404.
 *
 * <p>spec-004.
 */
@Service
public class RecipeService {

    /** Service-input for {@link #create}. {@code type} defaults to CUSTOM when null. */
    public record CreateRecipeInput(String machineId, String name, String description, RecipeType type) {
    }

    private final RecipeRepository recipes;
    private final ActionRepository actions;
    private final MachineService machineService;

    public RecipeService(RecipeRepository recipes, ActionRepository actions, MachineService machineService) {
        this.recipes = recipes;
        this.actions = actions;
        this.machineService = machineService;
    }

    /** Creates a recipe on one of the current user's machines. */
    @Transactional
    public Recipe create(CreateRecipeInput input) {
        String name = input.name();
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        // requireMachine scopes to the current user and 404s a not-owned machine.
        Machine machine = machineService.requireMachine(input.machineId());
        Recipe recipe = new Recipe();
        recipe.setMachine(machine);
        recipe.setName(name.trim());
        recipe.setDescription(input.description());
        recipe.setType(input.type() == null ? RecipeType.CUSTOM : input.type());
        return recipes.save(recipe);
    }

    /**
     * The current user's recipe by id.
     *
     * @throws RecipeNotFoundException 404 if absent or owned by another user.
     */
    public Recipe requireRecipe(String id) {
        return recipes.findByIdAndMachine_Owner_Id(id, CurrentUser.require().userId())
                .orElseThrow(() -> new RecipeNotFoundException(id));
    }

    /** The current user's recipes on a given owned machine. */
    public List<Recipe> listForMachine(String machineId) {
        return recipes.findByMachine_IdAndMachine_Owner_Id(machineId, CurrentUser.require().userId());
    }

    /** The actions of one of the current user's recipes, ordered by name. */
    public List<Action> listActions(String recipeId) {
        Recipe recipe = requireRecipe(recipeId);
        return actions.findByRecipe_IdOrderByName(recipe.getId());
    }
}
