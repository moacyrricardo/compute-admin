package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.discovery.model.DiscoveryEnablement;
import com.iskeru.computeadmin.discovery.repository.DiscoveryEnablementRepository;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-machine discovery enablement (spec-035): which {@link DiscovererFamily} families
 * are allowed to probe a machine. Enablement is a <strong>capability</strong> decision
 * (may this tool poke docker on my box?), distinct from — and upstream of — the approval
 * gate: enabling a family only lets its discoverers probe and <em>propose</em>; every
 * proposed action still lands {@code PENDING_APPROVAL} and needs UI approval to run.
 * Disabling a family stops future probing; it never removes already-approved recipes.
 *
 * <p>The effective enabled set is each family's {@link DiscovererFamily#defaultEnabled()
 * account-level default} overlaid with the machine's explicit
 * {@link DiscoveryEnablement} overrides — so docker is off until opted in and everything
 * else is on, with only deviations persisted. Every method scopes to the current user by
 * resolving the machine through {@link MachineService#requireMachine(String)} first; a
 * not-owned or absent machine reads as 404 (existence never leaked).
 *
 * <p>This supersedes the interim {@code ca.discovery.docker.enabled} global flag
 * (spec-033): docker discovery now runs when the {@link DiscovererFamily#DOCKER} family
 * is enabled <em>for that machine</em>.
 *
 * <p>spec-035.
 */
@Service
public class DiscoveryEnablementService {

    /** One family and whether it is effectively enabled for a machine (spec-035). */
    public record FamilyState(DiscovererFamily family, boolean enabled) {
    }

    private final DiscoveryEnablementRepository enablements;
    private final MachineService machineService;

    public DiscoveryEnablementService(DiscoveryEnablementRepository enablements,
                                      MachineService machineService) {
        this.enablements = enablements;
        this.machineService = machineService;
    }

    /**
     * Every family with its effective enabled state for one of the current user's
     * machines, in enum order (the UI toggle list).
     *
     * @throws com.iskeru.computeadmin.machine.service.MachineNotFoundException 404 if the
     *         machine is absent or owned by another user.
     */
    public List<FamilyState> familyStates(String machineId) {
        machineService.requireMachine(machineId);
        Map<DiscovererFamily, Boolean> overrides = overrides(machineId);
        return java.util.Arrays.stream(DiscovererFamily.values())
                .map(f -> new FamilyState(f, overrides.getOrDefault(f, f.defaultEnabled())))
                .toList();
    }

    /**
     * The families effectively enabled for one of the current user's machines — the set
     * {@link DiscoveryService} filters its discoverers by before probing.
     *
     * @throws com.iskeru.computeadmin.machine.service.MachineNotFoundException 404 if the
     *         machine is absent or owned by another user.
     */
    public Set<DiscovererFamily> enabledFamilies(String machineId) {
        machineService.requireMachine(machineId);
        Map<DiscovererFamily, Boolean> overrides = overrides(machineId);
        Set<DiscovererFamily> enabled = EnumSet.noneOf(DiscovererFamily.class);
        for (DiscovererFamily family : DiscovererFamily.values()) {
            if (overrides.getOrDefault(family, family.defaultEnabled())) {
                enabled.add(family);
            }
        }
        return enabled;
    }

    /**
     * Enables or disables a family for one of the current user's machines, upserting the
     * override row. Idempotent — setting a family to its current effective state is a
     * no-op write of the same value.
     *
     * @throws com.iskeru.computeadmin.machine.service.MachineNotFoundException 404 if the
     *         machine is absent or owned by another user.
     */
    @Transactional
    public DiscoveryEnablement setEnabled(String machineId, DiscovererFamily family, boolean enabled) {
        Machine machine = machineService.requireMachine(machineId);
        DiscoveryEnablement row = enablements.findByMachine_IdAndFamily(machineId, family)
                .orElseGet(() -> {
                    DiscoveryEnablement created = new DiscoveryEnablement();
                    created.setMachine(machine);
                    created.setFamily(family);
                    return created;
                });
        row.setEnabled(enabled);
        row.setUpdatedAt(Instant.now());
        return enablements.save(row);
    }

    /** The machine's explicit overrides, keyed by family (defaults fill the gaps). */
    private Map<DiscovererFamily, Boolean> overrides(String machineId) {
        Map<DiscovererFamily, Boolean> overrides = new EnumMap<>(DiscovererFamily.class);
        for (DiscoveryEnablement row : enablements.findByMachine_Id(machineId)) {
            overrides.put(row.getFamily(), row.isEnabled());
        }
        return overrides;
    }
}
