package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.discovery.service.DiscoveryEnablementService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.DiscoveryOutcome;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.ScriptPinService;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutionException;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshSession;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.function.Supplier;

import static com.iskeru.computeadmin.discovery.Proposals.literal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The spec-070 L0 degrade behaviour of {@link DiscoveryService}: a single discoverer
 * failing with a transport {@link SshExecutionException} skips <em>that family</em>,
 * marks the run <strong>partial</strong>, and still returns the proposals the earlier
 * families produced — instead of the pre-fix behaviour where the first refusal aborted
 * the whole run and discarded every good proposal. A <em>non-transport</em> exception
 * (a bug in a discoverer) is still allowed to abort loudly.
 *
 * <p>Three ordered fakes drive it: a good discoverer (NGINX) that always proposes, a
 * transport-thrower (DATABASE) that throws {@code SshExecutionException}, and an
 * abort-thrower (DOCKER, default-off) that throws a plain {@code RuntimeException}.
 * Per-machine enablement selects which run in each test.
 *
 * <p>spec-070.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DiscoveryService.class, DiscoveryEnablementService.class, RecipeService.class,
        ActionService.class, ApprovalService.class, ScriptPinService.class, MachineService.class,
        DiscoveryDegradeTest.FakeDiscoverersConfig.class})
class DiscoveryDegradeTest {

    private static final SshTarget TARGET = new SshTarget("host", 22, "deploy");

    /** A discoverer that always proposes one recipe — the "others' proposals" that must survive. */
    static class GoodDiscoverer implements RecipeDiscoverer, Ordered {
        @Override
        public DiscovererFamily family() {
            return DiscovererFamily.NGINX;
        }

        @Override
        public int getOrder() {
            return 1;
        }

        @Override
        public List<ProposedRecipe> discover(Machine machine, SshSession session) {
            ProposedAction action = new ProposedAction("test-config", "Read-only.", false,
                    List.of(literal("nginx"), literal("-t")), List.of());
            return List.of(new ProposedRecipe(RecipeType.NGINX, "good-service",
                    "Survives a later family's transport failure.", List.of(action)));
        }
    }

    /** Throws a transport {@link SshExecutionException} — the family L0 must degrade, not abort. */
    static class TransportThrower implements RecipeDiscoverer, Ordered {
        @Override
        public DiscovererFamily family() {
            return DiscovererFamily.DATABASE;
        }

        @Override
        public int getOrder() {
            return 2;
        }

        @Override
        public List<ProposedRecipe> discover(Machine machine, SshSession session) {
            throw new SshExecutionException(TARGET, new java.io.IOException("connection refused"));
        }
    }

    /** Throws a non-transport bug — must still abort the run (never silently degraded). */
    static class AbortThrower implements RecipeDiscoverer, Ordered {
        @Override
        public DiscovererFamily family() {
            return DiscovererFamily.DOCKER;   // default-off; enabled only by the abort test
        }

        @Override
        public int getOrder() {
            return 3;
        }

        @Override
        public List<ProposedRecipe> discover(Machine machine, SshSession session) {
            throw new IllegalStateException("discoverer bug — not a transport failure");
        }
    }

    @TestConfiguration
    static class FakeDiscoverersConfig {
        @Bean
        GoodDiscoverer goodDiscoverer() {
            return new GoodDiscoverer();
        }

        @Bean
        TransportThrower transportThrower() {
            return new TransportThrower();
        }

        @Bean
        AbortThrower abortThrower() {
            return new AbortThrower();
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
    private AppUserRepository users;

    @Test
    void discover_TransportFailureInOneFamily_ReturnsOthersAndFlagsPartial() {
        AppUser alice = saveUser("alice@example.com");
        asUser(alice, () -> {
            String machineId = register();
            // DOCKER default-off → the abort-thrower stays out; good (NGINX) then the
            // transport-thrower (DATABASE) run.
            DiscoveryOutcome outcome = discoveryService.discover(machineId);

            // The earlier family's proposal survived the later family's transport failure...
            assertThat(outcome.recipes()).extracting(d -> d.recipe().getName()).contains("good-service");
            // ...and the run is flagged partial, naming the family that could not be probed.
            assertThat(outcome.partial()).isTrue();
            assertThat(outcome.failedFamilies()).containsExactly(DiscovererFamily.DATABASE);
            return null;
        });
    }

    @Test
    void discover_NonTransportException_StillAborts() {
        AppUser bob = saveUser("bob@example.com");
        asUser(bob, () -> {
            String machineId = register();
            // Silence the transport-thrower (disable DATABASE) and let the abort-thrower run
            // (enable DOCKER) so a non-transport bug reaches the loop.
            enablement.setEnabled(machineId, DiscovererFamily.DATABASE, false);
            enablement.setEnabled(machineId, DiscovererFamily.DOCKER, true);

            assertThatThrownBy(() -> discoveryService.discover(machineId))
                    .isInstanceOf(IllegalStateException.class);
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
