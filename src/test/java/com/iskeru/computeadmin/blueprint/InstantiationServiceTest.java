package com.iskeru.computeadmin.blueprint;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.blueprint.model.BlueprintAction;
import com.iskeru.computeadmin.blueprint.model.RecipeBlueprint;
import com.iskeru.computeadmin.blueprint.service.BlueprintService;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.AddBlueprintActionInput;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.CreateBlueprintInput;
import com.iskeru.computeadmin.blueprint.service.BlueprintService.EditBlueprintActionInput;
import com.iskeru.computeadmin.blueprint.service.InstantiationService;
import com.iskeru.computeadmin.blueprint.service.InstantiationService.InstantiateInput;
import com.iskeru.computeadmin.blueprint.service.InstantiationService.InstantiatedRecipe;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Instantiation and reconciliation (spec-010): a blueprint copied onto machines
 * yields per-machine PENDING_APPROVAL recipes/actions with provenance; a no-op
 * re-instantiation preserves an approval; a re-instantiation after a content edit
 * resets the previously-approved action to DRAFT (the 004 content-hash rule). Never
 * approves, never runs.
 *
 * <p>spec-010.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({InstantiationService.class, BlueprintService.class, RecipeService.class,
        ActionService.class, ApprovalService.class, MachineService.class})
class InstantiationServiceTest {

    @Autowired
    private InstantiationService instantiationService;

    @Autowired
    private BlueprintService blueprintService;

    @Autowired
    private MachineService machineService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private AppUserRepository users;

    private AppUser alice;

    @BeforeEach
    void seedUser() {
        alice = saveUser("alice@example.com");
    }

    @Test
    void instantiate_OntoExplicitMachines_CreatesPendingRecipesWithProvenance() {
        asUser(alice, () -> {
            Machine m1 = machineService.register(new RegisterMachineInput("h1", 22, "root"));
            Machine m2 = machineService.register(new RegisterMachineInput("h2", 22, "root"));
            Machine m3 = machineService.register(new RegisterMachineInput("h3", 22, "root"));
            RecipeBlueprint blueprint = seedBlueprint();

            List<InstantiatedRecipe> results = instantiationService.instantiate(blueprint.getId(),
                    new InstantiateInput(Set.of(m1.getId(), m2.getId(), m3.getId()), null));

            assertThat(results).hasSize(3);
            for (InstantiatedRecipe result : results) {
                assertThat(result.recipe().getSourceBlueprintId()).isEqualTo(blueprint.getId());
                assertThat(result.recipe().getSourceBlueprintVersion()).isEqualTo(1);
                assertThat(result.actions()).hasSize(1);
                assertThat(result.actions().get(0).getApprovalState())
                        .isEqualTo(ApprovalState.PENDING_APPROVAL);
            }
            return null;
        });
    }

    @Test
    void instantiate_ByTag_TargetsOnlyTaggedMachines() {
        asUser(alice, () -> {
            Machine tagged = machineService.register(new RegisterMachineInput("web1", 22, "root"));
            machineService.register(new RegisterMachineInput("db1", 22, "root"));
            machineService.tag(tagged.getId(), Set.of("web"));
            RecipeBlueprint blueprint = seedBlueprint();

            List<InstantiatedRecipe> results = instantiationService.instantiate(blueprint.getId(),
                    new InstantiateInput(null, "web"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).recipe().getMachine().getId()).isEqualTo(tagged.getId());
            assertThat(results.get(0).actions().get(0).getApprovalState())
                    .isEqualTo(ApprovalState.PENDING_APPROVAL);
            return null;
        });
    }

    @Test
    void reinstantiate_UnchangedBlueprint_PreservesApproval() {
        asUser(alice, () -> {
            Machine m1 = machineService.register(new RegisterMachineInput("h1", 22, "root"));
            RecipeBlueprint blueprint = seedBlueprint();

            List<InstantiatedRecipe> first = instantiationService.instantiate(blueprint.getId(),
                    new InstantiateInput(Set.of(m1.getId()), null));
            String actionId = first.get(0).actions().get(0).getId();
            Action approved = approvalService.approve(actionId);
            assertThat(approved.getApprovalState()).isEqualTo(ApprovalState.APPROVED);

            List<InstantiatedRecipe> second = instantiationService.instantiate(blueprint.getId(),
                    new InstantiateInput(Set.of(m1.getId()), null));

            assertThat(second).hasSize(1);
            assertThat(second.get(0).actions()).hasSize(1);
            assertThat(second.get(0).actions().get(0).getId()).isEqualTo(actionId);
            assertThat(second.get(0).actions().get(0).getApprovalState())
                    .isEqualTo(ApprovalState.APPROVED);
            return null;
        });
    }

    @Test
    void reinstantiate_AfterBlueprintActionEdit_ResetsApprovedToDraft() {
        asUser(alice, () -> {
            Machine m1 = machineService.register(new RegisterMachineInput("h1", 22, "root"));
            RecipeBlueprint blueprint = seedBlueprint();
            BlueprintAction blueprintAction = blueprintService.listActions(blueprint.getId()).get(0);

            List<InstantiatedRecipe> first = instantiationService.instantiate(blueprint.getId(),
                    new InstantiateInput(Set.of(m1.getId()), null));
            String actionId = first.get(0).actions().get(0).getId();
            approvalService.approve(actionId);

            // Change the blueprint action's content (systemctl restart -> systemctl reload)
            // and bump the version, then re-instantiate.
            blueprintService.editBlueprintAction(blueprintAction.getId(), new EditBlueprintActionInput(
                    "restart nginx", "reload instead of restart", true,
                    List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl"),
                            new ArgTokenInput(TokenKind.LITERAL, "reload"),
                            new ArgTokenInput(TokenKind.PARAM, "svc")),
                    List.of(new ParamDefInput("svc", ParamKind.ALLOWED_SET, null, null, null,
                            List.of("nginx", "docker")))));

            List<InstantiatedRecipe> second = instantiationService.instantiate(blueprint.getId(),
                    new InstantiateInput(Set.of(m1.getId()), null));

            assertThat(second.get(0).actions()).hasSize(1);
            assertThat(second.get(0).actions().get(0).getId()).isEqualTo(actionId);
            assertThat(second.get(0).actions().get(0).getApprovalState())
                    .isEqualTo(ApprovalState.DRAFT);
            return null;
        });
    }

    /** A one-action NGINX blueprint owned by the current user. */
    private RecipeBlueprint seedBlueprint() {
        RecipeBlueprint blueprint = blueprintService.createBlueprint(
                new CreateBlueprintInput("nginx ops", "shared nginx recipe", RecipeType.NGINX));
        blueprintService.addBlueprintAction(new AddBlueprintActionInput(
                blueprint.getId(), "restart nginx", "restart the service", true,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl"),
                        new ArgTokenInput(TokenKind.LITERAL, "restart"),
                        new ArgTokenInput(TokenKind.PARAM, "svc")),
                List.of(new ParamDefInput("svc", ParamKind.ALLOWED_SET, null, null, null,
                        List.of("nginx", "docker")))));
        return blueprint;
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
