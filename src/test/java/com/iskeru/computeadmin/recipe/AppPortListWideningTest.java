package com.iskeru.computeadmin.recipe;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.repository.RecipeRepository;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.RecipeService.CreateRecipeInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The widened {@code app_port_list} column (spec-056, migration V15): the unioned
 * discovery sweeps multiply the item count and enlarge each item, so the pre-fill JSON
 * outgrows the old {@code VARCHAR(4000)}. Proves a &gt;4000-char list round-trips
 * intact now that the column is a {@code CLOB} — under the old bound this write threw a
 * data-truncation error, silently dropping discovered apps.
 *
 * <p>spec-056.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({RecipeService.class, MachineService.class})
class AppPortListWideningTest {

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private MachineService machineService;

    @Autowired
    private RecipeRepository recipes;

    @Autowired
    private AppUserRepository users;

    private AppUser alice;

    @BeforeEach
    void seedUser() {
        alice = saveUser("alice@example.com");
    }

    @Test
    void refreshDiscoveredAppPortList_WithOver4000Chars_RoundTripsIntact() {
        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", "host", 22, "deploy"));
            Recipe recipe = recipeService.create(
                    new CreateRecipeInput(machine.getId(), "generic app monitor", null, RecipeType.MONITOR));

            String largeJson = largeAppPortListJson(200); // ~200 items ⇒ well past 4000 chars
            assertThat(largeJson.length()).isGreaterThan(4000);

            recipeService.refreshDiscoveredAppPortList(recipe.getId(), largeJson);
            recipes.flush();

            Recipe reloaded = recipeService.requireRecipe(recipe.getId());
            assertThat(reloaded.getAppPortList()).isEqualTo(largeJson);
            return null;
        });
    }

    /** A JSON array of {@code count} discovery items, mirroring the persisted shape. */
    private static String largeAppPortListJson(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"appName\":\"app-").append(i)
                    .append("\",\"port\":").append(8000 + i)
                    .append(",\"runtime\":\"process\",\"scriptFolder\":\"/opt/lab/app-").append(i)
                    .append("/scripts\",\"contextKey\":\"/opt/lab/app-").append(i)
                    .append("\",\"contextDisplay\":\"/opt/lab/app-").append(i)
                    .append("\",\"sourceNote\":\"app folder · discovered via port + systemd unit\"}");
        }
        return sb.append(']').toString();
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
