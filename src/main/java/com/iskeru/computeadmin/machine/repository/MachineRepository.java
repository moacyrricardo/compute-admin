package com.iskeru.computeadmin.machine.repository;

import com.iskeru.computeadmin.machine.model.Machine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
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

    /**
     * Owner-scoped tag filter with OR semantics: the current user's machines that
     * carry <em>any</em> of {@code names}. The {@code machine_tag} join can yield the
     * same machine once per matching tag, so the caller de-duplicates by id (spec-018).
     */
    List<Machine> findByOwner_IdAndTags_NameIn(String ownerId, Collection<String> names);

    Optional<Machine> findByIdAndOwnerId(String id, String ownerId);

    boolean existsByOwnerIdAndHostAndPortAndLoginUser(String ownerId, String host, int port, String loginUser);
}
