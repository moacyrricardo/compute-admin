package com.iskeru.computeadmin.machine.repository;

import com.iskeru.computeadmin.machine.model.Machine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Machine}. All UI/MCP-facing lookups scope by
 * owner; {@code findAll()} (inherited) is the deliberate owner-bypassing call the
 * system-scoped connectivity job uses across the whole fleet.
 *
 * <p>spec-003.
 */
public interface MachineRepository extends JpaRepository<Machine, String> {

    List<Machine> findByOwnerId(String ownerId);

    List<Machine> findByOwnerIdAndTags_Name(String ownerId, String tag);

    Optional<Machine> findByIdAndOwnerId(String id, String ownerId);
}
