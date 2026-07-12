package com.iskeru.computeadmin.machine.service;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.UnauthorizedException;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.Tag;
import com.iskeru.computeadmin.machine.repository.MachineRepository;
import com.iskeru.computeadmin.machine.repository.TagRepository;
import jakarta.ws.rs.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Machine registry, scoped per user. Every method resolves
 * {@link CurrentUser#require()} and operates only within that user's machines and
 * tags; a not-owned or absent id reads as {@link MachineNotFoundException} (404,
 * existence never leaked).
 *
 * <p>Replaces the spec-002 stub (which proved the MCP seam before the registry
 * existed): {@link #list} now returns owner-scoped {@code Machine} entities.
 *
 * <p>spec-003.
 */
@Service
public class MachineService {

    /**
     * Service-input for {@link #register}. {@code port} is already resolved to a
     * concrete value by the caller (the RS defaults an omitted port to 22).
     */
    public record RegisterMachineInput(String host, int port, String loginUser) {
    }

    /**
     * Curated, deliberately small SSH-login-user → provisional tag map (spec-018,
     * source 1). Applied add-only at registration as a best guess; refined by the
     * real OS/cloud probe on first reach. {@code admin}/{@code root} carry no signal
     * and are intentionally absent. {@code ubuntu} is fuzzy (also the AWS Ubuntu AMI
     * user), which is exactly why source 2 corrects it.
     */
    private static final Map<String, String> LOGIN_USER_TAGS = Map.of(
            "ec2-user", "aws",
            "centos", "centos",
            "debian", "debian",
            "ubuntu", "ubuntu");

    private final MachineRepository machines;
    private final TagRepository tags;
    private final AppUserRepository users;

    public MachineService(MachineRepository machines, TagRepository tags, AppUserRepository users) {
        this.machines = machines;
        this.tags = tags;
        this.users = users;
    }

    /** Registers a machine owned by the current user. */
    @Transactional
    public Machine register(RegisterMachineInput input) {
        String host = input.host();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("host is required");
        }
        int port = input.port();
        if (port < 1 || port > 65535) {
            throw new BadRequestException("port must be between 1 and 65535");
        }
        String loginUser = input.loginUser();
        if (loginUser == null || loginUser.isBlank()) {
            throw new BadRequestException("loginUser is required");
        }
        AppUser owner = currentUser();
        String normalizedHost = host.trim();
        String normalizedLoginUser = loginUser.trim();
        // Pre-check the uq_machine_owner_host_port_user key so a re-registration
        // returns a clean 409 rather than surfacing the constraint violation as 500.
        if (machines.existsByOwnerIdAndHostAndPortAndLoginUser(
                owner.getId(), normalizedHost, port, normalizedLoginUser)) {
            throw new MachineAlreadyRegisteredException(normalizedHost, port, normalizedLoginUser);
        }
        Machine machine = new Machine();
        machine.setOwner(owner);
        machine.setHost(normalizedHost);
        machine.setPort(port);
        machine.setLoginUser(normalizedLoginUser);
        applyLoginUserTags(machine);
        return machines.save(machine);
    }

    /**
     * The current user's machines, all or filtered by {@code tags} (OR semantics —
     * a machine carrying any of the names matches). Null/blank names are ignored;
     * an empty effective filter returns every owned machine.
     */
    public List<Machine> list(List<String> tags) {
        String ownerId = CurrentUser.require().userId();
        List<String> names = tags == null ? List.of()
                : tags.stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        if (names.isEmpty()) {
            return machines.findByOwnerId(ownerId);
        }
        // The tag join repeats a machine once per matching name; de-dupe by id,
        // preserving encounter order.
        Map<String, Machine> distinct = new LinkedHashMap<>();
        for (Machine machine : machines.findByOwner_IdAndTags_NameIn(ownerId, names)) {
            distinct.putIfAbsent(machine.getId(), machine);
        }
        return List.copyOf(distinct.values());
    }

    /**
     * Applies the provisional {@link #LOGIN_USER_TAGS login-user tag} (source 1) to a
     * machine being registered, if its login user maps to one. Add-only and get-or-create,
     * so it never removes anything and creates no duplicate {@code Tag} rows (spec-018).
     */
    public void applyLoginUserTags(Machine machine) {
        String tag = LOGIN_USER_TAGS.get(machine.getLoginUser().toLowerCase(Locale.ROOT));
        if (tag != null) {
            addTag(machine.getOwner(), machine, tag);
        }
    }

    /**
     * Applies the OS/cloud tags detected by the read-only facts probe (source 2), on
     * the first successful reach. <strong>System-scoped</strong>: it loads the machine
     * by id (the facts-probe listener runs on an unbound pool thread) and get-or-creates
     * tags for the machine's own owner. Add-only, and guarded to run at most once per
     * machine by {@code factsProbedAt}, so a user-removed auto-tag is never re-added
     * (spec-018). A machine that has gone away, or that was already probed, is a no-op.
     */
    @Transactional
    public void applyDetectedFacts(String machineId, MachineFacts facts) {
        Machine machine = machines.findById(machineId).orElse(null);
        if (machine == null || machine.getFactsProbedAt() != null) {
            return;
        }
        AppUser owner = machine.getOwner();
        if (facts != null) {
            if (facts.os() != null) {
                addTag(owner, machine, facts.os());
            }
            if (facts.cloud() != null) {
                addTag(owner, machine, facts.cloud());
            }
        }
        // Mark probed even when nothing was detected: the box was reached and offered
        // no signal, so we do not keep re-probing it (facts_probed_at is @NotAudited,
        // so this opens no machine_aud revision — a liveness-derived marker, not a
        // config edit).
        machine.setFactsProbedAt(Instant.now());
    }

    /**
     * The current user's machine by id.
     *
     * @throws MachineNotFoundException 404 if absent or owned by another user.
     */
    public Machine requireMachine(String id) {
        return machines.findByIdAndOwnerId(id, CurrentUser.require().userId())
                .orElseThrow(() -> new MachineNotFoundException(id));
    }

    /** Adds {@code names} to a machine, get-or-creating each tag for the owner. */
    @Transactional
    public Machine tag(String id, Set<String> names) {
        Machine machine = requireMachine(id);
        AppUser owner = currentUser();
        for (String raw : names) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            addTag(owner, machine, raw.trim());
        }
        machine.setUpdatedAt(Instant.now());
        return machine;
    }

    /**
     * Adds one tag by name to a machine, get-or-creating the owner's {@link Tag} so
     * there are no duplicate rows and owner-scoping stays centralised. Add-only; the
     * membership set de-dupes. Shared by manual tagging and both auto-tag sources.
     */
    private void addTag(AppUser owner, Machine machine, String name) {
        Tag tag = tags.findByOwnerIdAndName(owner.getId(), name)
                .orElseGet(() -> {
                    Tag created = new Tag();
                    created.setOwner(owner);
                    created.setName(name);
                    return tags.save(created);
                });
        machine.getTags().add(tag);
    }

    /** Removes a tag from a machine by name (leaves the tag itself intact). */
    @Transactional
    public Machine untag(String id, String name) {
        Machine machine = requireMachine(id);
        machine.getTags().removeIf(tag -> tag.getName().equals(name));
        machine.setUpdatedAt(Instant.now());
        return machine;
    }

    private AppUser currentUser() {
        String userId = CurrentUser.require().userId();
        return users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
    }
}
