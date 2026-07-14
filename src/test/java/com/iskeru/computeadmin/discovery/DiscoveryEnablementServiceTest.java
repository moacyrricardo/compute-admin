package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.discovery.repository.DiscoveryEnablementRepository;
import com.iskeru.computeadmin.discovery.service.DiscoveryEnablementService;
import com.iskeru.computeadmin.discovery.service.DiscoveryEnablementService.FamilyState;
import com.iskeru.computeadmin.machine.model.Machine;
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

import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DiscoveryEnablementService} over a real H2 slice (spec-035): docker defaults
 * OFF and every other family defaults ON; toggles upsert a single override row; and
 * every operation is owner-scoped (another user's machine is a 404). Gating of actual
 * probing is covered by {@link DiscoveryGatingTest}.
 *
 * <p>spec-035.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DiscoveryEnablementService.class, MachineService.class})
class DiscoveryEnablementServiceTest {

    @Autowired
    private DiscoveryEnablementService enablement;

    @Autowired
    private MachineService machineService;

    @Autowired
    private DiscoveryEnablementRepository enablements;

    @Autowired
    private AppUserRepository users;

    private AppUser alice;

    @BeforeEach
    void seed() {
        alice = saveUser("alice@example.com");
    }

    @Test
    void defaults_DockerOff_EverythingElseOn() {
        asUser(alice, () -> {
            String machineId = register();
            Set<DiscovererFamily> enabled = enablement.enabledFamilies(machineId);

            assertThat(enabled).doesNotContain(DiscovererFamily.DOCKER);
            assertThat(enabled).contains(DiscovererFamily.NGINX, DiscovererFamily.DATABASE,
                    DiscovererFamily.CRON, DiscovererFamily.SYSTEMD, DiscovererFamily.HOST,
                    DiscovererFamily.APP);
            return null;
        });
    }

    @Test
    void familyStates_ListsEveryFamily_WithDockerDefaultOff() {
        asUser(alice, () -> {
            String machineId = register();

            assertThat(enablement.familyStates(machineId))
                    .extracting(FamilyState::family)
                    .containsExactly(DiscovererFamily.values());
            FamilyState docker = state(machineId, DiscovererFamily.DOCKER);
            assertThat(docker.enabled()).isFalse();
            return null;
        });
    }

    @Test
    void setEnabled_TurnsDockerOnAndNginxOff_AndReflectsInEffectiveSet() {
        asUser(alice, () -> {
            String machineId = register();

            enablement.setEnabled(machineId, DiscovererFamily.DOCKER, true);
            enablement.setEnabled(machineId, DiscovererFamily.NGINX, false);

            Set<DiscovererFamily> enabled = enablement.enabledFamilies(machineId);
            assertThat(enabled).contains(DiscovererFamily.DOCKER);
            assertThat(enabled).doesNotContain(DiscovererFamily.NGINX);
            return null;
        });
    }

    @Test
    void setEnabled_Twice_UpsertsOneRowNotTwo() {
        asUser(alice, () -> {
            String machineId = register();

            enablement.setEnabled(machineId, DiscovererFamily.DOCKER, true);
            enablement.setEnabled(machineId, DiscovererFamily.DOCKER, false);

            assertThat(enablements.findByMachine_Id(machineId))
                    .filteredOn(e -> e.getFamily() == DiscovererFamily.DOCKER).hasSize(1);
            assertThat(enablement.enabledFamilies(machineId)).doesNotContain(DiscovererFamily.DOCKER);
            return null;
        });
    }

    @Test
    void enabledFamilies_OnAnotherUsersMachine_Is404() {
        AppUser bob = saveUser("bob@example.com");
        String aliceMachineId = asUser(alice, this::register);

        assertThatThrownBy(() -> asUser(bob, () -> enablement.enabledFamilies(aliceMachineId)))
                .isInstanceOf(MachineNotFoundException.class);
    }

    @Test
    void setEnabled_OnAnotherUsersMachine_Is404() {
        AppUser bob = saveUser("bob@example.com");
        String aliceMachineId = asUser(alice, this::register);

        assertThatThrownBy(() -> asUser(bob,
                () -> enablement.setEnabled(aliceMachineId, DiscovererFamily.DOCKER, true)))
                .isInstanceOf(MachineNotFoundException.class);
    }

    private FamilyState state(String machineId, DiscovererFamily family) {
        return enablement.familyStates(machineId).stream()
                .filter(s -> s.family() == family).findFirst().orElseThrow();
    }

    private String register() {
        Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
        return machine.getId();
    }

    private AppUser saveUser(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
        user.setName(email.substring(0, email.indexOf('@')));
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }
}
