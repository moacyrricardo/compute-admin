package com.iskeru.computeadmin.blueprint.repository;

import com.iskeru.computeadmin.blueprint.model.BlueprintAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link BlueprintAction}. Owner-scoped lookups traverse
 * {@code blueprint.owner.id}; a not-owned or absent id is invisible, so the service
 * turns it into a 404 (existence never leaked).
 *
 * <p>spec-010.
 */
public interface BlueprintActionRepository extends JpaRepository<BlueprintAction, String> {

    Optional<BlueprintAction> findByIdAndBlueprint_Owner_Id(String id, String ownerId);

    List<BlueprintAction> findByBlueprint_IdOrderByName(String blueprintId);
}
