package com.iskeru.computeadmin.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.service.AppMonitorDiscoverer;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.repository.ActionRepository;
import com.iskeru.computeadmin.recipe.repository.RecipeRepository;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
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
 * App-monitor discovery reconciles in place (spec-021/025): re-discovering a box on
 * which a <strong>new</strong> app has appeared refreshes the matching family recipe's
 * pre-filled {@code (app-name, port)} list — a runtime value, not part of the content
 * hash (spec-022) — without minting a duplicate recipe/card.
 *
 * <p>spec-025.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DiscoveryService.class, RecipeService.class, ActionService.class, ApprovalService.class,
        MachineService.class, AppMonitorDiscoverer.class, AppMonitorReconcileTest.FakeSshConfig.class})
class AppMonitorReconcileTest {

    /** Mutable ss output so a re-discovery can add a second app between calls. */
    private static volatile String ssOutput = "";

    @TestConfiguration
    static class FakeSshConfig {
        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new FakeSshExecutor(AppMonitorReconcileTest::respond);
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
    private RecipeRepository recipes;

    @Autowired
    private ActionRepository actions;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ObjectMapper json;

    private AppUser alice;

    @BeforeEach
    void seed() {
        alice = saveUser("alice-appmon@example.com");
        ssOutput = oneApp();
    }

    @Test
    void reDiscover_NewApp_RefreshesPreFilledListInPlace_NoDuplicateRecipe() throws Exception {
        String machineId = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", 22, "deploy"));
            discoveryService.discover(machine.getId());

            // First pass: one uvicorn app pre-filled on the fastapi recipe.
            Recipe fastapi = recipes.findByMachine_IdAndMachine_Owner_IdAndTypeAndName(
                    machine.getId(), alice.getId(), RecipeType.MONITOR, "fastapi monitor").orElseThrow();
            assertThat(fastapi.getAppPortList()).contains("orders").contains("8000");

            // A second uvicorn app comes up; re-discover.
            ssOutput = twoApps();
            discoveryService.discover(machine.getId());
            return machine.getId();
        });

        // Still exactly one fastapi recipe (no duplicate card) — its pre-filled list now
        // carries both apps, refreshed in place.
        List<Recipe> fastapiRecipes = recipes.findByMachine_IdAndMachine_Owner_Id(machineId, alice.getId())
                .stream().filter(r -> r.getName().equals("fastapi monitor")).toList();
        assertThat(fastapiRecipes).hasSize(1);

        Recipe refreshed = fastapiRecipes.get(0);
        JsonNode items = json.readTree(refreshed.getAppPortList());
        assertThat(items).hasSize(2);
        assertThat(items).extracting(n -> n.get("appName").asText())
                .containsExactlyInAnyOrder("orders", "billing");
        assertThat(items).extracting(n -> n.get("port").asInt())
                .containsExactlyInAnyOrder(8000, 8001);

        // And no duplicate action rows within the reconciled recipe.
        assertThat(actions.findByRecipe_IdOrderByName(refreshed.getId()))
                .extracting(a -> a.getName()).doesNotHaveDuplicates();
    }

    private static String oneApp() {
        return "State Recv-Q Send-Q Local Address:Port Peer Address:Port Process\n"
                + "LISTEN 0 128 127.0.0.1:8000 0.0.0.0:* users:((\"uvicorn\",pid=2000,fd=6))";
    }

    private static String twoApps() {
        return oneApp() + "\n"
                + "LISTEN 0 128 127.0.0.1:8001 0.0.0.0:* users:((\"uvicorn\",pid=2001,fd=7))";
    }

    private static ExecResult respond(List<String> argv) {
        return switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ssOutput);
            case "cat /proc/2000/cmdline" -> ok("uvicorn orders.main:app");
            case "cat /proc/2001/cmdline" -> ok("uvicorn billing.main:app");
            case "cat /proc/2000/cgroup", "cat /proc/2001/cgroup" -> ok("0::/user.slice/session-1.scope");
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
