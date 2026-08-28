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
import com.iskeru.computeadmin.ssh.SessionWork;
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
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.function.Supplier;

import static com.iskeru.computeadmin.discovery.Proposals.literal;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-070 total-outage branch: when the one session cannot even be opened
 * (connect/auth fails before any probe runs), discovery <strong>degrades rather than
 * aborts</strong> — it returns an empty, {@code partial} result instead of letting the
 * {@code SshExecutionException} escape as a 502. A fake executor whose
 * {@code withSession} throws stands in for the unreachable host.
 *
 * <p>spec-070.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DiscoveryService.class, DiscoveryEnablementService.class, RecipeService.class,
        ActionService.class, ApprovalService.class, ScriptPinService.class, MachineService.class,
        DiscoveryConnectionLostTest.FakeConfig.class})
class DiscoveryConnectionLostTest {

    /** A discoverer that would propose if it ever ran — it must not, since connect fails. */
    static class GoodDiscoverer implements RecipeDiscoverer {
        @Override
        public DiscovererFamily family() {
            return DiscovererFamily.NGINX;
        }

        @Override
        public List<ProposedRecipe> discover(Machine machine, SshSession session) {
            return List.of(new ProposedRecipe(RecipeType.NGINX, "never", "unreachable",
                    List.of(new ProposedAction("x", "d", false, List.of(literal("true")), List.of()))));
        }
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean
        GoodDiscoverer goodDiscoverer() {
            return new GoodDiscoverer();
        }

        @Bean
        @Primary
        SshExecutor unreachableSsh() {
            return new SshExecutor() {
                @Override
                public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
                    throw new SshExecutionException(target, new java.io.IOException("unreachable"));
                }

                @Override
                public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
                    sink.onComplete(-1);
                }

                @Override
                public <T> T withSession(SshTarget target, SessionWork<T> work) {
                    // connect/auth fails before any probe — the total-outage branch.
                    throw new SshExecutionException(target, new java.io.IOException("connect refused"));
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
    private MachineService machineService;

    @Autowired
    private AppUserRepository users;

    @Test
    void discover_SessionCannotBeOpened_DegradesToEmptyPartial_NotA502() {
        AppUser alice = saveUser("alice@example.com");
        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));

            // The connect failure is caught and degraded — no exception escapes.
            DiscoveryOutcome outcome = discoveryService.discover(machine.getId());

            assertThat(outcome.partial()).isTrue();
            assertThat(outcome.recipes()).isEmpty();       // nothing probed, nothing persisted
            assertThat(outcome.failedFamilies()).isEmpty(); // no single family — the whole session failed
            return null;
        });
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
