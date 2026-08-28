package com.iskeru.computeadmin.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.discovery.service.DiscoveryEnablementService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.discovery.service.DockerComposeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.monitor.service.MonitorService;
import com.iskeru.computeadmin.monitor.service.MonitorService.AppPort;
import com.iskeru.computeadmin.monitor.service.MonitorService.DockerConsumerData;
import com.iskeru.computeadmin.monitor.service.MonitorService.MachineMonitors;
import com.iskeru.computeadmin.monitor.service.MonitorService.MonitorRecipe;
import com.iskeru.computeadmin.recipe.model.Recipe;
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

import java.util.List;
import java.util.function.Supplier;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-061 combined-object seam over a real H2 slice: {@link DiscoveryService#persist}
 * serialises a docker proposal as one {@code {dockerConsumers,appPortList}} object into the
 * {@code app_port_list} CLOB, and {@link MonitorService}'s tolerant readers pull both members back —
 * {@code parseDockerConsumers} the consumers, {@code parseAppPortList} the enriched items — off the
 * same value. Plus the old-format regressions the readers must keep parsing: a bare native array,
 * and a pre-061 bare {@code {dockerConsumers}} object with no {@code appPortList} member.
 *
 * <p>spec-061.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DiscoveryService.class, RecipeService.class, ActionService.class, ApprovalService.class,
        ScriptPinService.class, MachineService.class, MonitorService.class, DockerComposeDiscoverer.class,
        DiscoveryEnablementService.class, DockerCombinedObjectPersistenceTest.FakeSshConfig.class})
class DockerCombinedObjectPersistenceTest {

    private static final String PS_JSON =
            "{\"ID\":\"aaaaaaaaaaaa\",\"Names\":\"orders-db-1\",\"Image\":\"postgres:16\","
                    + "\"Labels\":\"com.docker.compose.project=orders,com.docker.compose.service=db\"}";

    private static final String INSPECT_JSON =
            "{\"Id\":\"aaaaaaaaaaaa1111\",\"Name\":\"/orders-db-1\","
                    + "\"Config\":{\"Image\":\"postgres:16\","
                    + "\"Env\":[\"POSTGRES_PASSWORD=nope\",\"PGDATA=/var/lib/postgresql/data\"]},"
                    + "\"Mounts\":[{\"Type\":\"volume\",\"Source\":\"/var/lib/docker/volumes/orders_db/_data\","
                    + "\"Destination\":\"/var/lib/postgresql/data\"}],"
                    + "\"NetworkSettings\":{\"Ports\":{\"5432/tcp\":[{\"HostPort\":\"5432\"}]}}}";

    @TestConfiguration
    static class FakeSshConfig {
        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new FakeSshExecutor(DockerCombinedObjectPersistenceTest::respond);
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
    private DiscoveryEnablementService enablement;
    @Autowired
    private RecipeService recipeService;
    @Autowired
    private RecipeRepository recipes;
    @Autowired
    private AppUserRepository users;

    private AppUser alice;

    @BeforeEach
    void seed() {
        alice = saveUser("alice-combined@example.com");
    }

    @Test
    void discover_PersistsCombinedObject_BothMembersReadBack() {
        MachineMonitors monitors = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            enablement.setEnabled(machine.getId(), DiscovererFamily.DOCKER, true);
            discoveryService.discover(machine.getId());

            // The CLOB holds ONE combined object: both members plus the translated host path.
            String column = recipes.findByMachine_IdAndMachine_Owner_IdAndTypeAndName(
                    machine.getId(), alice.getId(), RecipeType.MONITOR, "orders").orElseThrow().getAppPortList();
            assertThat(column).contains("\"dockerConsumers\"").contains("\"appPortList\"")
                    .contains("/var/lib/docker/volumes/orders_db/_data")
                    .doesNotContain("nope"); // the POSTGRES_PASSWORD secret never serialised

            return monitorService.listMonitors().get(0);
        });

        MonitorRecipe orders = monitors.recipes().stream()
                .filter(r -> r.recipe().getName().equals("orders")).findFirst().orElseThrow();
        // parseDockerConsumers still reads the consumers off the combined object.
        assertThat(orders.dockerConsumers()).extracting(DockerConsumerData::name).containsExactly("orders");
        // parseAppPortList now reads the appPortList member off the same object — and, since
        // spec-063, surfaces the 061-enriched context side-data (contextDisplay/confidence/
        // scriptFolder) the docker inspect enrichment persists alongside the base item.
        AppPort dbItem = orders.appPortList().stream()
                .filter(a -> a.appName().equals("orders-db-1")).findFirst().orElseThrow();
        assertThat(dbItem.port()).isEqualTo(5432);
        assertThat(dbItem.runtime()).isEqualTo("docker");
        assertThat(dbItem.contextDisplay()).isEqualTo("orders");
        assertThat(dbItem.confidence()).isEqualTo("high");
        assertThat(dbItem.scriptFolder()).isEqualTo("/var/lib/docker/volumes/orders_db/_data");
        // A docker-runtime item routes to the docker channel — it is never a native consumer.
        assertThat(orders.nativeConsumers()).isEmpty();
    }

    @Test
    void readers_TolerateOldFormats_BareArrayAndBareDockerConsumersObject() {
        MachineMonitors monitors = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));

            Recipe nativeRecipe = recipeService.getOrCreateDiscovered(
                    machine.getId(), RecipeType.MONITOR, "legacy-native", "old bare array");
            recipeService.refreshDiscoveredAppPortList(nativeRecipe.getId(),
                    "[{\"appName\":\"orders\",\"port\":8080,\"runtime\":\"systemd\"}]");

            Recipe legacyDocker = recipeService.getOrCreateDiscovered(
                    machine.getId(), RecipeType.MONITOR, "legacy-docker", "pre-061 consumers-only object");
            recipeService.refreshDiscoveredAppPortList(legacyDocker.getId(),
                    "{\"dockerConsumers\":[{\"name\":\"cache\",\"role\":\"DATABASE\",\"dedication\":\"SHARED\"}]}");

            return monitorService.listMonitors().get(0);
        });

        MonitorRecipe nativeR = recipe(monitors, "legacy-native");
        assertThat(nativeR.appPortList()).containsExactly(new AppPort("orders", 8080, "systemd"));
        assertThat(nativeR.dockerConsumers()).isEmpty();

        MonitorRecipe dockerR = recipe(monitors, "legacy-docker");
        // A pre-061 bare {dockerConsumers} object has no appPortList member ⇒ the empty list.
        assertThat(dockerR.appPortList()).isEmpty();
        assertThat(dockerR.dockerConsumers()).extracting(DockerConsumerData::name).containsExactly("cache");
    }

    private static MonitorRecipe recipe(MachineMonitors monitors, String name) {
        return monitors.recipes().stream()
                .filter(r -> r.recipe().getName().equals(name)).findFirst().orElseThrow();
    }

    private static ExecResult respond(List<String> argv) {
        if (argv.equals(List.of("command", "-v", "docker"))) {
            return ok("/usr/bin/docker");
        }
        if (argv.equals(List.of("docker", "ps", "--format", "{{json .}}"))) {
            return ok(PS_JSON);
        }
        if (argv.size() >= 4 && argv.get(0).equals("docker") && argv.get(1).equals("inspect")) {
            return ok(INSPECT_JSON);
        }
        return notFound();
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
