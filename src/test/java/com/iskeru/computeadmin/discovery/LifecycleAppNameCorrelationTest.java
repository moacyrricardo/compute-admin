package com.iskeru.computeadmin.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.service.AppMonitorDiscoverer;
import com.iskeru.computeadmin.discovery.service.DiscoveryEnablementService;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.RecipeService.CreateRecipeInput;
import com.iskeru.computeadmin.recipe.service.ScriptPinService;
import com.iskeru.computeadmin.ssh.SshExecutor;
import jakarta.ws.rs.BadRequestException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression for the spec-050 discovery failure. A lifecycle action's argv is a single
 * literal script path (e.g. {@code /opt/orders/run.sh}), and it carries the reserved
 * scalar {@code app-name} ALLOWED_SET only to correlate the action to its app card
 * (spec-026) — no {@code PARAM} token references it. The structural validator
 * ({@code ActionService.applyStructure}) must accept that: the reserved {@code app-name}
 * is a correlation label, not a command input. Every OTHER unreferenced param stays
 * rejected.
 *
 * <p>Reproduces the live "Param declared but never referenced: app-name" that broke
 * {@code DiscoveryService.reconcileAction} → {@code ActionService.addAction} — a path
 * the pure-discoverer {@code LifecycleDiscovererTest} never persisted through.
 *
 * <p>spec-050.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({DiscoveryService.class, RecipeService.class, ActionService.class, ApprovalService.class,
        ScriptPinService.class, MachineService.class, AppMonitorDiscoverer.class,
        DiscoveryEnablementService.class, LifecycleAppNameCorrelationTest.FakeSshConfig.class})
class LifecycleAppNameCorrelationTest {

    @TestConfiguration
    static class FakeSshConfig {
        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new FakeSshExecutor(argv -> FakeSshExecutor.ok(""));
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private MachineService machineService;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private ActionService actionService;

    @Autowired
    private AppUserRepository users;

    private AppUser alice;

    @BeforeEach
    void seed() {
        alice = saveUser("alice-lifecycle@example.com");
    }

    @Test
    void reservedAppName_correlationOnly_isAcceptedWithoutBeingReferenced() {
        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            Recipe recipe = recipeService.create(new CreateRecipeInput(
                    machine.getId(), "lifecycle orders", "Lifecycle scripts for orders.", RecipeType.CUSTOM));

            // The exact spec-050 shape: argv is a single literal script path; `app-name`
            // is declared as a correlation ALLOWED_SET, referenced by no token.
            AddActionInput input = new AddActionInput(
                    recipe.getId(), "start", "Run /opt/orders/run.sh", false,
                    List.of(new ArgTokenInput(TokenKind.LITERAL, "/opt/orders/run.sh")),
                    List.of(new ParamDefInput("app-name", ParamKind.ALLOWED_SET,
                            null, null, null, List.of("orders"))));

            Action action = actionService.addAction(input);

            assertThat(action.getId()).isNotBlank();
            assertThat(action.getParamDefs()).extracting(d -> d.getName()).containsExactly("app-name");
            assertThat(action.getArgTokens()).hasSize(1);
            return null;
        });
    }

    @Test
    void nonReservedUnreferencedParam_stillRejected() {
        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("h2", "h2", 22, "deploy"));
            Recipe recipe = recipeService.create(new CreateRecipeInput(
                    machine.getId(), "lifecycle billing", "x", RecipeType.CUSTOM));

            AddActionInput input = new AddActionInput(
                    recipe.getId(), "start", "x", false,
                    List.of(new ArgTokenInput(TokenKind.LITERAL, "/opt/billing/run.sh")),
                    List.of(new ParamDefInput("colour", ParamKind.ALLOWED_SET,
                            null, null, null, List.of("red"))));

            assertThatThrownBy(() -> actionService.addAction(input))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("never referenced");
            return null;
        });
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
