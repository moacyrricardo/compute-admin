package com.iskeru.computeadmin.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.discovery.service.DockerComposeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.monitor.api.MonitorDtos.ConsumerServiceView;
import com.iskeru.computeadmin.monitor.api.MonitorDtos.MonitorConsumerView;
import com.iskeru.computeadmin.monitor.api.MonitorDtos.MonitorMachineView;
import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.ConsumerSource;
import com.iskeru.computeadmin.monitor.model.Dedication;
import com.iskeru.computeadmin.monitor.service.MonitorService;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.repository.RecipeRepository;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.ScriptPinService;
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
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.function.Supplier;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The docker compose discovery vertical end to end (spec-033): {@link
 * DockerComposeDiscoverer} classifies {@code docker ps} into consumers, {@link
 * DiscoveryService} persists them onto the recipe's un-audited {@code appPortList}
 * column (no new schema), and {@link MonitorService}/{@code MonitorMachineView} read
 * them back as {@code source=DOCKER} {@code MonitorConsumerView}s with the datastore
 * role/dedication/owner intact. Re-discovery reconciles by {@code (machine, MONITOR,
 * project)} — one recipe per project, no duplicate.
 *
 * <p>Runs with {@code ca.discovery.docker.enabled=true} (the interim opt-in, spec-035).
 *
 * <p>spec-033.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "ca.discovery.docker.enabled=true")
@Import({DiscoveryService.class, RecipeService.class, ActionService.class, ApprovalService.class,
        ScriptPinService.class, MachineService.class, MonitorService.class, DockerComposeDiscoverer.class,
        DockerComposeReconcileTest.FakeSshConfig.class})
class DockerComposeReconcileTest {

    private static String row(String names, String image, String labels) {
        return "{\"Names\":\"" + names + "\",\"Image\":\"" + image + "\",\"Labels\":\"" + labels + "\"}";
    }

    private static final String PS_JSON = String.join("\n",
            row("orders-web-1", "orders/web:latest", "com.docker.compose.project=orders,com.docker.compose.service=web"),
            row("orders-db-1", "postgres:16", "com.docker.compose.project=orders,com.docker.compose.service=db"),
            row("cache", "redis:7", ""));

    @TestConfiguration
    static class FakeSshConfig {
        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new FakeSshExecutor(DockerComposeReconcileTest::respond);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private DiscoveryService discoveryService;

    @Autowired
    private MachineService machineService;

    @Autowired
    private MonitorService monitorService;

    @Autowired
    private RecipeRepository recipes;

    @Autowired
    private AppUserRepository users;

    private AppUser alice;

    @BeforeEach
    void seed() {
        alice = saveUser("alice-docker@example.com");
    }

    @Test
    void discover_PersistsDockerConsumers_SurfacedByMonitorRead_WithClassification() {
        MonitorMachineView view = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            discoveryService.discover(machine.getId());

            // The compose project persisted as one (machine, MONITOR, "orders") recipe,
            // its consumers stored on the un-audited appPortList column (no new schema).
            // spec-038: the project's postgres rides as a role=DATABASE SERVICE (not a
            // separate DEDICATED consumer), so its container name is in the persisted JSON.
            assertThat(recipes.findByMachine_IdAndMachine_Owner_IdAndTypeAndName(
                    machine.getId(), alice.getId(), RecipeType.MONITOR, "orders").orElseThrow()
                    .getAppPortList()).contains("dockerConsumers").contains("orders-db-1");

            return MonitorMachineView.of(monitorService.listMonitors().get(0));
        });

        // The monitor read surfaces the docker consumers with source=DOCKER. spec-038: the
        // project is ONE consumer whose services carry its datastore — orders-db-1 is NOT a
        // top-level consumer; only "orders" (the project) and "cache" (standalone) surface.
        assertThat(view.consumers()).extracting(MonitorConsumerView::name)
                .containsExactlyInAnyOrder("orders", "cache");
        assertThat(view.consumers()).allSatisfy(c ->
                assertThat(c.source()).isEqualTo(ConsumerSource.DOCKER));

        MonitorConsumerView orders = consumer(view, "orders");
        assertThat(orders.role()).isEqualTo(ConsumerRole.APP);
        // The project carries all its services — app AND datastore — each tagged with role.
        assertThat(orders.services()).extracting(ConsumerServiceView::name)
                .containsExactly("orders-web-1", "orders-db-1");
        assertThat(service(orders, "orders-web-1").role()).isEqualTo(ConsumerRole.APP);
        assertThat(service(orders, "orders-db-1").role()).isEqualTo(ConsumerRole.DATABASE);

        // The standalone redis stays its own SHARED datastore consumer (unchanged).
        assertThat(consumer(view, "cache").dedication()).isEqualTo(Dedication.SHARED);
    }

    @Test
    void reDiscover_IsIdempotent_OneRecipePerProject() {
        String machineId = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            discoveryService.discover(machine.getId());
            discoveryService.discover(machine.getId());
            return machine.getId();
        });

        List<?> ordersRecipes = recipes.findByMachine_IdAndMachine_Owner_Id(machineId, alice.getId())
                .stream().filter(r -> r.getType() == RecipeType.MONITOR && r.getName().equals("orders")).toList();
        assertThat(ordersRecipes).hasSize(1);
    }

    private static MonitorConsumerView consumer(MonitorMachineView view, String name) {
        return view.consumers().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static ConsumerServiceView service(MonitorConsumerView consumer, String name) {
        return consumer.services().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    private static ExecResult respond(List<String> argv) {
        return switch (String.join(" ", argv)) {
            case "command -v docker" -> ok("/usr/bin/docker");
            case "docker ps --format {{json .}}" -> ok(PS_JSON);
            default -> notFound();
        };
    }

    private AppUser saveUser(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
        user.setName("alice");
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }
}
