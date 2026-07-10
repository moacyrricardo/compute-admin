package com.iskeru.computeadmin.machine;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.repository.TagRepository;
import com.iskeru.computeadmin.machine.service.MachineAlreadyRegisteredException;
import com.iskeru.computeadmin.machine.service.MachineNotFoundException;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registry scoping and tagging (spec-003): register attaches the current user as
 * owner, tags are get-or-created per owner (deduped), list filters by tag within
 * the owner, and {@code requireMachine} on another user's machine is 404 (never
 * leaks existence).
 *
 * <p>spec-003.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(MachineService.class)
class MachineServiceTest {

    @Autowired
    private MachineService machineService;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private TagRepository tags;

    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void seedUsers() {
        alice = saveUser("alice@example.com");
        bob = saveUser("bob@example.com");
    }

    @Test
    void register_ScopesMachineToCurrentUser() {
        Machine machine = asUser(alice, () -> machineService.register(
                new RegisterMachineInput("host-a", 22, "root")));

        assertThat(machine.getId()).isNotBlank();
        assertThat(machine.getOwner().getId()).isEqualTo(alice.getId());
        assertThat(asUser(alice, () -> machineService.list(null))).hasSize(1);
        assertThat(asUser(bob, () -> machineService.list(null))).isEmpty();
    }

    @Test
    void register_DuplicateHostPortLoginUser_ThrowsAlreadyRegistered() {
        asUser(alice, () -> machineService.register(new RegisterMachineInput("host-a", 22, "root")));

        assertThatThrownBy(() -> asUser(alice, () -> machineService.register(
                new RegisterMachineInput("  host-a  ", 22, "  root  "))))
                .isInstanceOf(MachineAlreadyRegisteredException.class);
    }

    @Test
    void register_SameHostForAnotherUser_IsAllowed() {
        asUser(alice, () -> machineService.register(new RegisterMachineInput("shared-host", 22, "root")));

        Machine bobMachine = asUser(bob, () -> machineService.register(
                new RegisterMachineInput("shared-host", 22, "root")));

        assertThat(bobMachine.getId()).isNotBlank();
        assertThat(bobMachine.getOwner().getId()).isEqualTo(bob.getId());
    }

    @Test
    void tag_ReusesTheOwnersExistingTag() {
        Machine one = asUser(alice, () -> machineService.register(
                new RegisterMachineInput("host-1", 22, "root")));
        Machine two = asUser(alice, () -> machineService.register(
                new RegisterMachineInput("host-2", 22, "root")));

        asUser(alice, () -> machineService.tag(one.getId(), Set.of("prod")));
        asUser(alice, () -> machineService.tag(two.getId(), Set.of("prod")));

        assertThat(tags.findAll()).hasSize(1);
    }

    @Test
    void list_ByTag_ReturnsOnlyMatchingOwnedMachines() {
        Machine prod = asUser(alice, () -> machineService.register(
                new RegisterMachineInput("prod-host", 22, "root")));
        asUser(alice, () -> machineService.register(
                new RegisterMachineInput("dev-host", 22, "root")));
        asUser(alice, () -> machineService.tag(prod.getId(), Set.of("prod")));

        List<Machine> matched = asUser(alice, () -> machineService.list("prod"));

        assertThat(matched).extracting(Machine::getId).containsExactly(prod.getId());
    }

    @Test
    void requireMachine_OnAnotherUsersMachine_Is404() {
        Machine aliceMachine = asUser(alice, () -> machineService.register(
                new RegisterMachineInput("secret-host", 22, "root")));

        assertThatThrownBy(() -> asUser(bob, () -> machineService.requireMachine(aliceMachine.getId())))
                .isInstanceOf(MachineNotFoundException.class);
    }

    private AppUser saveUser(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setGoogleSub("dev|" + email);
        user.setName(email.substring(0, email.indexOf('@')));
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }
}
