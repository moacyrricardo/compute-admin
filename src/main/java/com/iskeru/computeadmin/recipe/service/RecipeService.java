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
import java.util.Optional;

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
     * Get-or-create a {@code CUSTOM} recipe named {@code name} on the current user's
     * machine {@code machineId}: reuse an existing user-owned {@code CUSTOM} recipe
     * with that name on that machine, else create one. This is what lets several
     * custom commands (each its own action) be grouped under one named recipe.
     *
     * <p>spec-007.
     */
    @Transactional
    public Recipe getOrCreateCustom(String machineId, String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("recipeName is required");
        }
        String trimmed = name.trim();
        // requireMachine (via create) scopes to the current user and 404s a not-owned
        // machine; the lookup below is likewise owner-scoped, so no cross-user reuse.
        return recipes.findByMachine_IdAndMachine_Owner_IdAndTypeAndName(
                        machineId, CurrentUser.require().userId(), RecipeType.CUSTOM, trimmed)
                .orElseGet(() -> create(
                        new CreateRecipeInput(machineId, trimmed, null, RecipeType.CUSTOM)));
    }

    /**
     * Get-or-create the recipe a discoverer owns on machine {@code machineId},
     * identified by the triple {@code (machine, type, name)}: reuse an existing
     * owner-scoped recipe matched by that triple, else create one. This is what makes
     * re-running discovery idempotent — a second probe reuses the same recipe instead
     * of minting a duplicate. Mirrors {@link #getOrCreateCustom} but generalises the
     * match beyond {@code CUSTOM} to any discovered {@link RecipeType}. Owner scoping
     * is preserved because the finder joins {@code machine.owner.id} to the current
     * user; a not-owned machine 404s inside {@link #create}.
     *
     * <p>spec-021.
     */
    @Transactional
    public Recipe getOrCreateDiscovered(String machineId, RecipeType type, String name, String description) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        String trimmed = name.trim();
        return recipes.findByMachine_IdAndMachine_Owner_IdAndTypeAndName(
                        machineId, CurrentUser.require().userId(), type, trimmed)
                .orElseGet(() -> create(new CreateRecipeInput(machineId, trimmed, description, type)));
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

    /**
     * The recipe already instantiated from {@code sourceBlueprintId} onto one of the
     * current user's machines, if any (spec 010). Used by {@code InstantiationService}
     * to reconcile a re-instantiation onto the same machine.
     */
    public Optional<Recipe> findInstantiated(String machineId, String sourceBlueprintId) {
        return recipes.findByMachine_IdAndMachine_Owner_IdAndSourceBlueprintId(
                machineId, CurrentUser.require().userId(), sourceBlueprintId);
    }

    /**
     * Creates a recipe on one of the current user's machines carrying blueprint
     * provenance (spec 010): {@code sourceBlueprintId} + {@code sourceBlueprintVersion}
     * record where it was instantiated from.
     */
    @Transactional
    public Recipe createInstantiated(String machineId, String name, String description, RecipeType type,
                                     String sourceBlueprintId, int sourceBlueprintVersion) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("name is required");
        }
        Machine machine = machineService.requireMachine(machineId);
        Recipe recipe = new Recipe();
        recipe.setMachine(machine);
        recipe.setName(name.trim());
        recipe.setDescription(description);
        recipe.setType(type == null ? RecipeType.CUSTOM : type);
        recipe.setSourceBlueprintId(sourceBlueprintId);
        recipe.setSourceBlueprintVersion(sourceBlueprintVersion);
        return recipes.save(recipe);
    }

    /**
     * Refreshes an instantiated recipe's display fields and its recorded blueprint
     * version on re-instantiation (spec 010). The recipe must already be one of the
     * current user's (scoped by the caller that located it).
     */
    @Transactional
    public Recipe updateInstantiatedMeta(String recipeId, String name, String description, RecipeType type,
                                         int sourceBlueprintVersion) {
        Recipe recipe = requireRecipe(recipeId);
        recipe.setName(name == null || name.isBlank() ? recipe.getName() : name.trim());
        recipe.setDescription(description);
        recipe.setType(type == null ? RecipeType.CUSTOM : type);
        recipe.setSourceBlueprintVersion(sourceBlueprintVersion);
        return recipes.save(recipe);
    }
}
