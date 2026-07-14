package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.discovery.service.CronDiscoverer;
import com.iskeru.computeadmin.discovery.service.DatabaseDiscoverer;
import com.iskeru.computeadmin.discovery.service.DiscoveryEnablementService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.DiscoveredRecipe;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.ReconcileOutcome;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.ReconciledAction;
import com.iskeru.computeadmin.discovery.service.DockerDiscoverer;
import com.iskeru.computeadmin.discovery.service.MonitorMachineDiscoverer;
import com.iskeru.computeadmin.discovery.service.NginxDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.repository.ActionRepository;
import com.iskeru.computeadmin.recipe.repository.RecipeRepository;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.ScriptPinService;
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
        ScriptPinService.class, MachineService.class, DiscoveryEnablementService.class,
        NginxDiscoverer.class, DockerDiscoverer.class, DatabaseDiscoverer.class, CronDiscoverer.class,
        MonitorMachineDiscoverer.class, DiscoveryServiceTest.FakeSshConfig.class})
class DiscoveryServiceTest {

    /** Verbs that would mean an action template — not a probe — was executed. */
    private static final List<String> MUTATING_TOKENS = List.of(
            "restart", "reload", "stop", "start", "rm", "ln", "mysqldump", "pg_dump", "status");

    /**
     * Mutable `docker ps` probe output so a re-discovery test can change the running
     * container set between calls (the attacker-influenced ALLOWED_SET, S3). Reset in
     * {@link #seedUser()} so each test starts from the single container {@code web}.
     */
    private static volatile String dockerPsOutput = "web\n";

    @TestConfiguration
    static class FakeSshConfig {
        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new FakeSshExecutor(DiscoveryServiceTest::respond);
        }

        // @DataJpaTest does not autoconfigure Jackson; DiscoveryService needs an
        // ObjectMapper to serialise app-monitor pre-fill lists (spec-025).
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
    private DiscoveryEnablementService enablement;

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
    private ApprovalService approvalService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AppUser alice;

    @BeforeEach
    void seedUser() {
        alice = saveUser("alice@example.com");
        dockerPsOutput = "web\n";
        // The fake bean is a context-cached singleton reused across methods; reset its
        // recordings so each test asserts only its own probes.
        FakeSshExecutor fake = (FakeSshExecutor) ssh;
        fake.commands.clear();
        fake.transactionActive.clear();
    }

    @Test
    void discover_PersistsProposalsAsPendingApprovalAndNeverApproves() {
        List<DiscoveredRecipe> discovered = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            // Docker discovery is default-off (spec-035); this test asserts the docker
            // recipe/actions, so opt this machine into the DOCKER family first.
            enablement.setEnabled(machine.getId(), DiscovererFamily.DOCKER, true);
            return discoveryService.discover(machine.getId());
        });

        // Every built-in service was detected → a recipe per type (mysql is DATABASE).
        assertThat(discovered).extracting(d -> d.recipe().getType())
                .contains(RecipeType.NGINX, RecipeType.DOCKER, RecipeType.DATABASE, RecipeType.CRON);

        List<Action> actions = discovered.stream()
                .flatMap(d -> d.actions().stream()).map(ReconciledAction::action).toList();
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
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
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
                machineService.register(new RegisterMachineInput("host", "host", 22, "deploy")).getId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        asUser(bob, () -> discoveryService.discover(aliceMachineId)))
                .isInstanceOf(com.iskeru.computeadmin.machine.service.MachineNotFoundException.class);
    }

    @Test
    void reDiscover_IsIdempotent_NoDuplicateRecipesOrActions() {
        String machineId = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            discoveryService.discover(machine.getId());
            discoveryService.discover(machine.getId());
            return machine.getId();
        });

        // One recipe per (machine, type, name) triple — not a second copy per re-run.
        List<Recipe> persisted = recipes.findByMachine_IdAndMachine_Owner_Id(machineId, alice.getId());
        assertThat(persisted).isNotEmpty();
        assertThat(persisted).extracting(r -> r.getType() + "/" + r.getName()).doesNotHaveDuplicates();

        // And no duplicate action names within any reconciled recipe.
        for (Recipe recipe : persisted) {
            assertThat(actions.findByRecipe_IdOrderByName(recipe.getId()))
                    .extracting(Action::getName).doesNotHaveDuplicates();
        }
    }

    @Test
    void discover_ProposesUniversalMonitorMachine_PendingApprovalNotAutoApproved() {
        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            discoveryService.discover(machine.getId());

            // The universal host monitor is proposed on every reachable box (spec-023):
            // exactly one (machine, MONITOR, "monitor machine") recipe...
            Recipe monitor = recipes.findByMachine_IdAndMachine_Owner_IdAndTypeAndName(
                    machine.getId(), alice.getId(), RecipeType.MONITOR, "monitor machine").orElseThrow();
            List<Action> vitals = actions.findByRecipe_IdOrderByName(monitor.getId());

            // ...with the four read-only, param-free host-vitals actions (cores/nproc
            // is the spec-037 docker CPU-axis denominator), all PENDING_APPROVAL —
            // MONITOR grants no auto-approval or read-only carve-out.
            assertThat(vitals).extracting(Action::getName)
                    .containsExactlyInAnyOrder("cpu", "memory", "disk", "cores");
            assertThat(vitals).allSatisfy(action -> {
                assertThat(action.getApprovalState()).isEqualTo(ApprovalState.PENDING_APPROVAL);
                assertThat(action.getApprovedByUserId()).isNull();
                assertThat(action.isSudo()).isFalse();
                assertThat(action.getParamDefs()).isEmpty();
            });
            return null;
        });
    }

    @Test
    void reDiscover_MonitorMachine_DoesNotDuplicateHostPanel() {
        String machineId = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            discoveryService.discover(machine.getId());
            discoveryService.discover(machine.getId());
            return machine.getId();
        });

        // Re-discovery reconciles in place (spec-021): still one host-monitor recipe and
        // one action per name — the direct "no duplicate host panel" guard.
        List<Recipe> monitors = recipes.findByMachine_IdAndMachine_Owner_Id(machineId, alice.getId()).stream()
                .filter(r -> r.getType() == RecipeType.MONITOR && r.getName().equals("monitor machine"))
                .toList();
        assertThat(monitors).hasSize(1);
        assertThat(actions.findByRecipe_IdOrderByName(monitors.get(0).getId()))
                .extracting(Action::getName).containsExactlyInAnyOrder("cpu", "memory", "disk", "cores");
    }

    @Test
    void reDiscover_ApprovedIdentical_SurvivesUnchanged() {
        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            enablement.setEnabled(machine.getId(), DiscovererFamily.DOCKER, true);
            discoveryService.discover(machine.getId());

            Recipe docker = recipes.findByMachine_IdAndMachine_Owner_IdAndTypeAndName(
                    machine.getId(), alice.getId(), RecipeType.DOCKER, "docker").orElseThrow();
            Action ps = actions.findByRecipe_IdAndName(docker.getId(), "ps").orElseThrow();
            approvalService.approve(ps.getId());

            // Re-discover with identical probe output.
            ReconciledAction reconciled = reconciledActionNamed(
                    discoveryService.discover(machine.getId()), "ps");
            assertThat(reconciled.outcome()).isEqualTo(ReconcileOutcome.UNCHANGED);
            assertThat(reconciled.action().getApprovalState()).isEqualTo(ApprovalState.APPROVED);
            assertThat(reconciled.proposed()).isNull();
            return null;
        });
    }

    @Test
    void reDiscover_ApprovedDiffers_IsSurfacedNotDuplicated() {
        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            enablement.setEnabled(machine.getId(), DiscovererFamily.DOCKER, true);
            discoveryService.discover(machine.getId());

            Recipe docker = recipes.findByMachine_IdAndMachine_Owner_IdAndTypeAndName(
                    machine.getId(), alice.getId(), RecipeType.DOCKER, "docker").orElseThrow();
            Action restart = actions.findByRecipe_IdAndName(docker.getId(), "restart container").orElseThrow();
            approvalService.approve(restart.getId());

            // A new container appears in the ALLOWED_SET before the human re-approves.
            dockerPsOutput = "web\napi\n";
            ReconciledAction reconciled = reconciledActionNamed(
                    discoveryService.discover(machine.getId()), "restart container");

            // The approval is left intact and the diff surfaced — never auto-adopted.
            assertThat(reconciled.outcome()).isEqualTo(ReconcileOutcome.DIFFERS_AWAITING_REAPPROVAL);
            assertThat(reconciled.action().getApprovalState()).isEqualTo(ApprovalState.APPROVED);
            assertThat(reconciled.proposed()).isNotNull();
            // The stored (approved) action still bounds only the originally-approved set.
            assertThat(allowedContainers(reconciled.action())).containsExactly("web");

            // No duplicate "restart container" action was minted (single-valued lookup).
            assertThat(actions.findByRecipe_IdOrderByName(docker.getId()))
                    .filteredOn(a -> a.getName().equals("restart container")).hasSize(1);
            return null;
        });
    }

    @Test
    void reDiscover_NotYetApprovedDiffers_IsRefreshedInPlace() {
        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            enablement.setEnabled(machine.getId(), DiscovererFamily.DOCKER, true);
            discoveryService.discover(machine.getId());

            // Never approved; a new container appears before review.
            dockerPsOutput = "web\napi\n";
            ReconciledAction reconciled = reconciledActionNamed(
                    discoveryService.discover(machine.getId()), "restart container");

            assertThat(reconciled.outcome()).isEqualTo(ReconcileOutcome.REFRESHED);
            assertThat(reconciled.action().getApprovalState()).isEqualTo(ApprovalState.PENDING_APPROVAL);
            // The refreshed proposal picked up the new container in place.
            assertThat(allowedContainers(reconciled.action())).containsExactlyInAnyOrder("web", "api");
            return null;
        });
    }

    private static ReconciledAction reconciledActionNamed(List<DiscoveredRecipe> discovered, String name) {
        return discovered.stream().flatMap(d -> d.actions().stream())
                .filter(ra -> ra.action().getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static List<String> allowedContainers(Action action) {
        return action.getParamDefs().stream()
                .filter(def -> def.getName().equals("container"))
                .flatMap(def -> def.getAllowedValues().stream())
                .map(ParamAllowedValue::getValue)
                .toList();
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
            Machine machine = machineService.register(new RegisterMachineInput("probe-host", "probe-host", 22, "deploy"));
            return discoveryService.discover(machine.getId());
        });
        try {
            // The read-only probes ran, and none of them saw an active transaction.
            assertThat(fake.transactionActive).isNotEmpty();
            assertThat(fake.transactionActive).allMatch(active -> !active);

            // Proposals still land PENDING_APPROVAL; discovery never approves.
            List<Action> persisted = discovered.stream()
                    .flatMap(d -> d.actions().stream()).map(ReconciledAction::action).toList();
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
                d.actions().forEach(reconciled -> actions.deleteById(reconciled.action().getId()));
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
            case "docker ps --format {{.Names}}" -> ok(dockerPsOutput);
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
        user.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
        user.setName(email.substring(0, email.indexOf('@')));
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }
}
