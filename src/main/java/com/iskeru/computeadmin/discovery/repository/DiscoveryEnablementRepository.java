package com.iskeru.computeadmin.discovery.repository;

import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.discovery.model.DiscoveryEnablement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link DiscoveryEnablement} rows (spec-035). Lookups are by
 * machine (and family); owner-scoping is enforced above, in
 * {@code DiscoveryEnablementService}, by resolving the machine through
 * {@code MachineService#requireMachine} first — a repository is never reached
 * cross-module (ARCH dependency direction).
 *
 * <p>spec-035.
 */
public interface DiscoveryEnablementRepository extends JpaRepository<DiscoveryEnablement, String> {

    List<DiscoveryEnablement> findByMachine_Id(String machineId);

    Optional<DiscoveryEnablement> findByMachine_IdAndFamily(String machineId, DiscovererFamily family);
}
