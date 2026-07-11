package com.iskeru.computeadmin.recipe.repository;

import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Recipe}. Every lookup scopes by owner through
 * {@code machine.owner.id} — a not-owned or absent id is invisible, so the service
 * turns it into a 404 (existence never leaked).
 *
 * <p>spec-004.
 */
public interface RecipeRepository extends JpaRepository<Recipe, String> {

    Optional<Recipe> findByIdAndMachine_Owner_Id(String id, String ownerId);

    List<Recipe> findByMachine_Owner_Id(String ownerId);

    List<Recipe> findByMachine_IdAndMachine_Owner_Id(String machineId, String ownerId);

    Optional<Recipe> findByMachine_IdAndMachine_Owner_IdAndTypeAndName(
            String machineId, String ownerId, RecipeType type, String name);
    /** The recipe already instantiated from a blueprint onto a given owned machine, if any (spec 010). */
    Optional<Recipe> findByMachine_IdAndMachine_Owner_IdAndSourceBlueprintId(
            String machineId, String ownerId, String sourceBlueprintId);
}
