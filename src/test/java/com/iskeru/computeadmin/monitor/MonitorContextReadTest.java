package com.iskeru.computeadmin.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.ConsumerSource;
import com.iskeru.computeadmin.monitor.service.MonitorService;
import com.iskeru.computeadmin.monitor.service.MonitorService.AppPort;
import com.iskeru.computeadmin.monitor.service.MonitorService.MachineMonitors;
import com.iskeru.computeadmin.monitor.service.MonitorService.MonitorRecipe;
import com.iskeru.computeadmin.monitor.service.MonitorService.NativeConsumerData;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-063 read seam over a real H2 slice: {@link MonitorService#listMonitors} pulls the rich
 * discovery-context side-data back off the persisted {@code app_port_list} — {@code parseAppPortList}
 * now reads {@code contextDisplay}/{@code contextScripts}/{@code sourceNote}/{@code confidence}/{@code
 * scriptFolder} (the internal {@code contextKey} too, for grouping) — and derives the native-consumer
 * channel from it ({@code parseNativeConsumers}). Includes the tolerant-reader regressions: an old bare
 * row with none of the context fields, and a docker-object row (which routes to the docker channel, so
 * no native consumer).
 *
 * <p>spec-063.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({RecipeService.class, MachineService.class, MonitorService.class,
        MonitorContextReadTest.MapperConfig.class})
class MonitorContextReadTest {

    @TestConfiguration
    static class MapperConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private MachineService machineService;
    @Autowired
    private MonitorService monitorService;
    @Autowired
    private RecipeService recipeService;
    @Autowired
    private AppUserRepository users;

    private AppUser alice;

    @BeforeEach
    void seed() {
        alice = saveUser("alice-063@example.com");
    }

    @Test
    void listMonitors_ReadsRichContextFields_AndDerivesDatabaseNativeConsumer() {
        MachineMonitors monitors = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            Recipe pg = recipeService.getOrCreateDiscovered(
                    machine.getId(), RecipeType.MONITOR, "generic app monitor", "standalone pg");
            recipeService.refreshDiscoveredAppPortList(pg.getId(),
                    "[{\"appName\":\"postgres\",\"port\":5432,\"runtime\":\"process\","
                            + "\"contextKey\":\"/var/lib/postgresql\","
                            + "\"contextDisplay\":\"/var/lib/postgresql\","
                            + "\"contextScripts\":[\"postgres\"],"
                            + "\"sourceNote\":\"common service · postgres\","
                            + "\"confidence\":\"high\","
                            + "\"scriptFolder\":\"/var/lib/postgresql\"}]");
            return monitorService.listMonitors().get(0);
        });

        MonitorRecipe recipe = monitors.recipes().get(0);

        // parseAppPortList reads every rich field off the persisted row.
        AppPort item = recipe.appPortList().get(0);
        assertThat(item.appName()).isEqualTo("postgres");
        assertThat(item.port()).isEqualTo(5432);
        assertThat(item.contextKey()).isEqualTo("/var/lib/postgresql");
        assertThat(item.contextDisplay()).isEqualTo("/var/lib/postgresql");
        assertThat(item.contextScripts()).containsExactly("postgres");
        assertThat(item.sourceNote()).isEqualTo("common service · postgres");
        assertThat(item.confidence()).isEqualTo("high");
        assertThat(item.scriptFolder()).isEqualTo("/var/lib/postgresql");

        // The 058 standalone-pg group ⇒ exactly one DATABASE native consumer, source NATIVE.
        assertThat(recipe.nativeConsumers()).hasSize(1);
        NativeConsumerData consumer = recipe.nativeConsumers().get(0);
        assertThat(consumer.role()).isEqualTo(ConsumerRole.DATABASE);
        assertThat(consumer.source()).isEqualTo(ConsumerSource.NATIVE);
        assertThat(consumer.name()).isEqualTo("/var/lib/postgresql");
    }

    @Test
    void listMonitors_OldBareRow_DefaultsContextFieldsNull_AndYieldsAppConsumer() {
        MachineMonitors monitors = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            Recipe legacy = recipeService.getOrCreateDiscovered(
                    machine.getId(), RecipeType.MONITOR, "legacy-native", "pre-063 bare row");
            recipeService.refreshDiscoveredAppPortList(legacy.getId(),
                    "[{\"appName\":\"orders\",\"port\":8080,\"runtime\":\"systemd\"}]");
            return monitorService.listMonitors().get(0);
        });

        MonitorRecipe recipe = monitors.recipes().get(0);
        AppPort item = recipe.appPortList().get(0);
        // Absent keys default to null/empty — the pre-063 row parses unchanged.
        assertThat(item.contextKey()).isNull();
        assertThat(item.contextDisplay()).isNull();
        assertThat(item.contextScripts()).isEmpty();
        assertThat(item.sourceNote()).isNull();
        assertThat(item.confidence()).isNull();
        assertThat(item.scriptFolder()).isNull();

        // A context-less native app is its own APP consumer named by app name.
        assertThat(recipe.nativeConsumers()).extracting(NativeConsumerData::name).containsExactly("orders");
        assertThat(recipe.nativeConsumers().get(0).role()).isEqualTo(ConsumerRole.APP);
    }

    @Test
    void listMonitors_DockerObjectRow_ReadsAppPortList_ButRoutesNoNativeConsumer() {
        MachineMonitors monitors = asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            Recipe dockerRecipe = recipeService.getOrCreateDiscovered(
                    machine.getId(), RecipeType.MONITOR, "orders", "docker combined object");
            recipeService.refreshDiscoveredAppPortList(dockerRecipe.getId(),
                    "{\"dockerConsumers\":[{\"name\":\"orders\",\"role\":\"APP\"}],"
                            + "\"appPortList\":[{\"appName\":\"orders-db-1\",\"port\":5432,\"runtime\":\"docker\"}]}");
            return monitorService.listMonitors().get(0);
        });

        MonitorRecipe recipe = monitors.recipes().get(0);
        // parseAppPortList reads the appPortList member off the combined object; context absent ⇒ null.
        AppPort item = recipe.appPortList().get(0);
        assertThat(item.appName()).isEqualTo("orders-db-1");
        assertThat(item.contextDisplay()).isNull();
        // The docker-runtime item routes to the docker channel — no native consumer, no double-count.
        assertThat(recipe.nativeConsumers()).isEmpty();
        assertThat(recipe.dockerConsumers()).extracting(d -> d.name()).containsExactly("orders");
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
