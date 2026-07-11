package com.iskeru.computeadmin.recipe.repository;

import com.iskeru.computeadmin.recipe.model.Action;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Action}. Owner-scoped lookups traverse
 * {@code recipe.machine.owner.id}; a not-owned or absent id is invisible, so the
 * service turns it into a 404 (existence never leaked).
 *
 * <p>spec-004.
 */
public interface ActionRepository extends JpaRepository<Action, String> {

    Optional<Action> findByIdAndRecipe_Machine_Owner_Id(String id, String ownerId);

    List<Action> findByRecipe_IdOrderByName(String recipeId);
}
