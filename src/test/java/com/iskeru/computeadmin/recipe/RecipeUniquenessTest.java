package com.iskeru.computeadmin.recipe;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema-level idempotency invariant behind discovery reconciliation (spec-021):
 * the {@code uq_recipe_machine_type_name} constraint (migration V9) rejects a second
 * recipe sharing the identity triple {@code (machine, type, name)}, so a duplicate
 * can never be persisted even if service code slipped.
 *
 * <p>spec-021.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({RecipeService.class, MachineService.class})
class RecipeUniquenessTest {

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
    void duplicateMachineTypeName_isRejectedBySchema() {
        asUser(alice, () -> {
            Machine machine = machineService.register(new RegisterMachineInput("host", 22, "deploy"));
            recipeService.create(new CreateRecipeInput(machine.getId(), "docker", null, RecipeType.DOCKER));
            recipes.flush();

            assertThatThrownBy(() -> {
                recipeService.create(new CreateRecipeInput(machine.getId(), "docker", null, RecipeType.DOCKER));
                recipes.flush();
            }).isInstanceOf(DataIntegrityViolationException.class);
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
