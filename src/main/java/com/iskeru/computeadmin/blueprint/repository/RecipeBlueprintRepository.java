package com.iskeru.computeadmin.blueprint.repository;

import com.iskeru.computeadmin.blueprint.model.RecipeBlueprint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link RecipeBlueprint}. Every lookup scopes by
 * {@code owner.id} — blueprints are per-user and never shared, so a not-owned or
 * absent id is invisible and the service turns it into a 404 (existence never
 * leaked).
 *
 * <p>spec-010.
 */
public interface RecipeBlueprintRepository extends JpaRepository<RecipeBlueprint, String> {

    Optional<RecipeBlueprint> findByIdAndOwnerId(String id, String ownerId);

    List<RecipeBlueprint> findByOwnerIdOrderByName(String ownerId);
}
