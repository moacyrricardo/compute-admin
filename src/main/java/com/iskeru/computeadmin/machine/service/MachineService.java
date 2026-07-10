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
import java.util.List;
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
        return machines.save(machine);
    }

    /** The current user's machines, all or filtered by {@code tag}. */
    public List<Machine> list(String tag) {
        String ownerId = CurrentUser.require().userId();
        return (tag == null || tag.isBlank())
                ? machines.findByOwnerId(ownerId)
                : machines.findByOwnerIdAndTags_Name(ownerId, tag.trim());
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
            String name = raw.trim();
            Tag tag = tags.findByOwnerIdAndName(owner.getId(), name)
                    .orElseGet(() -> {
                        Tag created = new Tag();
                        created.setOwner(owner);
                        created.setName(name);
                        return tags.save(created);
                    });
            machine.getTags().add(tag);
        }
        machine.setUpdatedAt(Instant.now());
        return machine;
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
