package com.iskeru.computeadmin.run.repository;

import com.iskeru.computeadmin.run.model.Run;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link Run}. Owner-scoped lookups traverse
 * {@code action.recipe.machine.owner.id}; a not-owned or absent id is invisible, so
 * the service turns it into a 404 (existence never leaked).
 *
 * <p>spec-005.
 */
public interface RunRepository extends JpaRepository<Run, String> {

    Optional<Run> findByIdAndAction_Recipe_Machine_Owner_Id(String id, String ownerId);
}
