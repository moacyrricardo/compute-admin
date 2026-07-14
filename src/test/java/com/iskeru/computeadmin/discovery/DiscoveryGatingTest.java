package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.discovery.service.DiscoveryEnablementService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.DiscoveredRecipe;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.ReconciledAction;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.ScriptPinService;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.function.Supplier;

import static com.iskeru.computeadmin.discovery.Proposals.literal;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-035 enablement gate: {@link DiscoveryService} probes a {@link RecipeDiscoverer}
 * only when its {@link DiscovererFamily} is enabled for the machine. Two recording fake
 * discoverers — one in the default-off {@code DOCKER} family, one in the default-on
 * {@code NGINX} family — assert that enablement, not omission, decides whether a family is
 * probed. Crucially, enablement is <strong>not</strong> the approval gate: an enabled
 * discoverer's proposed action still lands {@code PENDING_APPROVAL}.
 *
 * <p>spec-035.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DiscoveryService.class, DiscoveryEnablementService.class, RecipeService.class,
        ActionService.class, ApprovalService.class, ScriptPinService.class, MachineService.class,
        DiscoveryGatingTest.FakeDiscoverersConfig.class})
class DiscoveryGatingTest {

    /** A {@link RecipeDiscoverer} that records whether it was probed and proposes one action. */
    static class RecordingDiscoverer implements RecipeDiscoverer {
        private final DiscovererFamily family;
        private final String recipeName;
        volatile boolean probed;

        RecordingDiscoverer(DiscovererFamily family, String recipeName) {
            this.family = family;
            this.recipeName = recipeName;
        }

        @Override
        public DiscovererFamily family() {
            return family;
        }

        @Override
        public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
            probed = true;
            ProposedAction action = new ProposedAction("restart", "Restart the service (mutating).",
                    false, List.of(literal("service"), literal("restart")), List.of());
            return List.of(new ProposedRecipe(RecipeType.DOCKER, recipeName,
                    "Fake proposal from " + family, List.of(action)));
        }
    }

    @TestConfiguration
    static class FakeDiscoverersConfig {
        @Bean
        RecordingDiscoverer dockerFake() {
            return new RecordingDiscoverer(DiscovererFamily.DOCKER, "docker-fake");
        }

        @Bean
        RecordingDiscoverer nginxFake() {
            return new RecordingDiscoverer(DiscovererFamily.NGINX, "nginx-fake");
        }

        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new SshExecutor() {
                @Override
                public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
                    return new ExecResult(1, "", "");
                }

                @Override
                public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
                    sink.onComplete(0);
                }
            };
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
    }

    @Autowired
    private DiscoveryService discoveryService;

    @Autowired
    private DiscoveryEnablementService enablement;

    @Autowired
    private MachineService machineService;

    @Autowired
    private RecordingDiscoverer dockerFake;

    @Autowired
    private RecordingDiscoverer nginxFake;

    @Autowired
    private AppUserRepository users;

    private AppUser alice;

    @BeforeEach
    void seed() {
        alice = saveUser("alice@example.com");
        dockerFake.probed = false;
        nginxFake.probed = false;
    }

    @Test
    void discover_DockerDefaultOff_DockerFamilyNotProbed_NginxFamilyProbed() {
        asUser(alice, () -> {
            String machineId = register();
            List<DiscoveredRecipe> discovered = discoveryService.discover(machineId);

            // Docker is default-off → never probed; the on-by-default nginx family is probed.
            assertThat(dockerFake.probed).isFalse();
            assertThat(nginxFake.probed).isTrue();
            // No docker proposal materialised (the disabled family produced nothing).
            assertThat(discovered).extracting(d -> d.recipe().getName()).contains("nginx-fake");
            assertThat(discovered).extracting(d -> d.recipe().getName()).doesNotContain("docker-fake");
            return null;
        });
    }

    @Test
    void discover_DockerEnabled_FamilyProbed_AndProposalStaysPendingApproval() {
        asUser(alice, () -> {
            String machineId = register();
            enablement.setEnabled(machineId, DiscovererFamily.DOCKER, true);

            List<DiscoveredRecipe> discovered = discoveryService.discover(machineId);

            assertThat(dockerFake.probed).isTrue();
            // Enablement is NOT the approval gate: the proposed action is still PENDING_APPROVAL.
            ReconciledAction action = discovered.stream()
                    .filter(d -> d.recipe().getName().equals("docker-fake"))
                    .flatMap(d -> d.actions().stream()).findFirst().orElseThrow();
            assertThat(action.action().getApprovalState()).isEqualTo(ApprovalState.PENDING_APPROVAL);
            assertThat(action.action().getApprovedByUserId()).isNull();
            return null;
        });
    }

    @Test
    void discover_NginxDisabled_FamilyNotProbed() {
        asUser(alice, () -> {
            String machineId = register();
            enablement.setEnabled(machineId, DiscovererFamily.NGINX, false);

            discoveryService.discover(machineId);

            assertThat(nginxFake.probed).isFalse();
            return null;
        });
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
