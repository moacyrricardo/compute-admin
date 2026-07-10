package com.iskeru.computeadmin.machine.repository;

import com.iskeru.computeadmin.machine.model.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link Tag}. Tagging is get-or-create per owner, so
 * the one lookup is by owner + name.
 *
 * <p>spec-003.
 */
public interface TagRepository extends JpaRepository<Tag, String> {

    Optional<Tag> findByOwnerIdAndName(String ownerId, String name);
}
