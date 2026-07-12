package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.service.CronDiscoverer;
import com.iskeru.computeadmin.discovery.service.DatabaseDiscoverer;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.DiscoveredRecipe;
import com.iskeru.computeadmin.discovery.service.DockerDiscoverer;
import com.iskeru.computeadmin.discovery.service.NginxDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.repository.ActionRepository;
import com.iskeru.computeadmin.recipe.repository.RecipeRepository;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.machine.repository.MachineRepository;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.SshExecutor;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DiscoveryService} over a real H2 slice (spec-006): proposals persist as
 * {@code PENDING_APPROVAL}, discovery never approves, and — crucially — no mutating
 * command is ever sent to the box (asserted against the fake executor's recorded
 * argv). A not-owned machine reads as 404.
 *
 * <p>spec-006.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DiscoveryService.class, RecipeService.class, ActionService.class, ApprovalService.class,
        MachineService.class, NginxDiscoverer.class, DockerDiscoverer.class, DatabaseDiscoverer.class,
        CronDiscoverer.class, DiscoveryServiceTest.FakeSshConfig.class})
class DiscoveryServiceTest {

    /** Verbs that would mean an action template — not a probe — was executed. */
    private static final List<String> MUTATING_TOKENS = List.of(
            "restart", "reload", "stop", "start", "rm", "ln", "mysqldump", "pg_dump", "status");

    @TestConfiguration
    static class FakeSshConfig {
        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new FakeSshExecutor(DiscoveryServiceTest::respond);
        }
    }

    @Autowired
    private DiscoveryService discoveryService;

    @Autowired
    private MachineService machineService;

    @Autowired
    private SshExecutor ssh;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private MachineRepository machines;

    @Autowired
    private RecipeRepository recipes;

    @Autowired
    private ActionRepository actions;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppUser alice;

    @BeforeEach
    void seedUser() {
        alice = saveUser("alice@example.com");
        // The fake bean is a context-cached singleton reused across methods; reset its
        // recordings so each test asserts only its own probes.
        FakeSshExecutor fake = (FakeSshExecutor) ssh;
        fake.commands.clear();
        fake.transactionActive.clear();
    }

    @Test
    void discover_PersistsProposalsAsPendingApprovalAndNeverApproves() {
        List<DiscoveredRecipe> discovered = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", 22, "deploy"));
            return discoveryService.discover(machine.getId());
        });

        // Every built-in service was detected → a recipe per type (mysql is DATABASE).
        assertThat(discovered).extracting(d -> d.recipe().getType())
                .contains(RecipeType.NGINX, RecipeType.DOCKER, RecipeType.DATABASE, RecipeType.CRON);

        List<Action> actions = discovered.stream().flatMap(d -> d.actions().stream()).toList();
        assertThat(actions).isNotEmpty();
        assertThat(actions).allSatisfy(action -> {
            assertThat(action.getApprovalState()).isEqualTo(ApprovalState.PENDING_APPROVAL);
            assertThat(action.getApprovedByUserId()).isNull();
            assertThat(action.getApprovedAt()).isNull();
            assertThat(action.getApprovedSnapshotHash()).isNull();
        });

        // A mutating template was proposed (proof the gate, not omission, is what holds it back)...
        assertThat(actions).extracting(Action::getName)
                .contains("restart", "enable-site", "disable-site", "restart container");
    }

    @Test
    void discover_NeverSendsAMutatingCommand() {
        FakeSshExecutor fake = (FakeSshExecutor) ssh;

        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", 22, "deploy"));
            return discoveryService.discover(machine.getId());
        });

        assertThat(fake.commands).isNotEmpty();
        assertThat(fake.commands).allSatisfy(argv ->
                assertThat(argv).doesNotContainAnyElementsOf(MUTATING_TOKENS));
        // Sanity: the read-only detection probes did run.
        assertThat(fake.commands).contains(List.of("command", "-v", "nginx"));
    }

    @Test
    void discover_OnAnotherUsersMachine_Is404() {
        AppUser bob = saveUser("bob@example.com");
        String aliceMachineId = asUser(alice, () ->
                machineService.register(new RegisterMachineInput("host", 22, "deploy")).getId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        asUser(bob, () -> discoveryService.discover(aliceMachineId)))
                .isInstanceOf(com.iskeru.computeadmin.machine.service.MachineNotFoundException.class);
    }

    // NOT_SUPPORTED so the probe phase runs with NO ambient transaction (the seam the
    // fake records) and the persist phase's own TransactionTemplate really commits.
    // Because that commits into the shared in-memory DB, it registers under a unique
    // owner and deletes its committed rows afterward (test-isolation hazard, spec-013).
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void discover_RunsProbesOutsideTransaction_AndPersistsPending() {
        AppUser owner = saveUser("probe-" + UUID.randomUUID() + "@example.com");
        FakeSshExecutor fake = (FakeSshExecutor) ssh;

        List<DiscoveredRecipe> discovered = asUser(owner, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("probe-host", 22, "deploy"));
            return discoveryService.discover(machine.getId());
        });
        try {
            // The read-only probes ran, and none of them saw an active transaction.
            assertThat(fake.transactionActive).isNotEmpty();
            assertThat(fake.transactionActive).allMatch(active -> !active);

            // Proposals still land PENDING_APPROVAL; discovery never approves.
            List<Action> persisted = discovered.stream().flatMap(d -> d.actions().stream()).toList();
            assertThat(persisted).isNotEmpty();
            assertThat(persisted).allSatisfy(action ->
                    assertThat(action.getApprovalState()).isEqualTo(ApprovalState.PENDING_APPROVAL));
        } finally {
            cleanUpCommitted(owner, discovered);
        }
    }

    /**
     * Deletes the rows the {@code NOT_SUPPORTED} probe test committed to the shared DB
     * — including the {@code @BeforeEach} {@code alice} user, which also commits under
     * a {@code NOT_SUPPORTED} method and would otherwise collide with every later test
     * that registers {@code alice@example.com}.
     */
    private void cleanUpCommitted(AppUser owner, List<DiscoveredRecipe> discovered) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            for (DiscoveredRecipe d : discovered) {
                d.actions().forEach(action -> actions.deleteById(action.getId()));
                recipes.deleteById(d.recipe().getId());
            }
            machines.findByOwnerId(owner.getId()).forEach(machine -> machines.deleteById(machine.getId()));
            users.deleteById(owner.getId());
            users.deleteById(alice.getId());
        });
    }

    private static ExecResult respond(List<String> argv) {
        return switch (String.join(" ", argv)) {
            case "command -v nginx" -> ok("/usr/sbin/nginx");
            case "nginx -t" -> ok("syntax is ok");
            case "ls /etc/nginx/sites-available" -> ok("default\n");
            case "ls /etc/nginx/sites-enabled" -> ok("default\n");
            case "command -v docker" -> ok("/usr/bin/docker");
            case "docker ps --format {{.Names}}" -> ok("web\n");
            case "command -v mysql" -> ok("/usr/bin/mysql");
            case "systemctl is-active mysql" -> ok("active");
            case "mysql -N -B -e SHOW DATABASES" -> ok("information_schema\nappdb\n");
            case "command -v crontab" -> ok("/usr/bin/crontab");
            case "crontab -l" -> ok("0 3 * * * /usr/local/bin/backup\n");
            case "ls /etc/cron.d" -> ok("e2scrub_all\n");
            default -> notFound();
        };
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
