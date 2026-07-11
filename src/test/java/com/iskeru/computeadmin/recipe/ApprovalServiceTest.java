package com.iskeru.computeadmin.recipe;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionNotFoundException;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.EditActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.IllegalApprovalTransitionException;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.RecipeService.CreateRecipeInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The approval state machine (spec-004): the full transition matrix, the
 * edit-resets-approval TOCTOU guard, that approve records the approver, and that
 * approving another user's action reads as 404 (existence never leaked).
 *
 * <p>spec-004.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ApprovalService.class, ActionService.class, RecipeService.class, MachineService.class})
class ApprovalServiceTest {

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ActionService actionService;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private MachineService machineService;

    @Autowired
    private AppUserRepository users;

    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void seedUsers() {
        alice = saveUser("alice@example.com");
        bob = saveUser("bob@example.com");
    }

    @Test
    void submitForApproval_FromDraft_MovesToPendingApproval() {
        Action action = asUser(alice, this::seedAction);

        Action submitted = asUser(alice, () -> approvalService.submitForApproval(action.getId()));

        assertThat(submitted.getApprovalState()).isEqualTo(ApprovalState.PENDING_APPROVAL);
    }

    @Test
    void submitForApproval_FromNonDraft_IsIllegalTransition() {
        Action action = asUser(alice, this::seedAction);
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));

        assertThatThrownBy(() -> asUser(alice, () -> approvalService.submitForApproval(action.getId())))
                .isInstanceOf(IllegalApprovalTransitionException.class);
    }

    @Test
    void approve_FromPendingApproval_ApprovesAndRecordsApprover() {
        Action action = asUser(alice, this::seedAction);
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));

        Action approved = asUser(alice, () -> approvalService.approve(action.getId()));

        assertThat(approved.getApprovalState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(approved.getApprovedSnapshotHash()).isNotBlank();
        assertThat(approved.getApprovedByUserId()).isEqualTo(alice.getId());
        assertThat(approved.getApprovedAt()).isNotNull();
    }

    @Test
    void approve_FromDraft_IsIllegalTransition() {
        Action action = asUser(alice, this::seedAction);

        assertThatThrownBy(() -> asUser(alice, () -> approvalService.approve(action.getId())))
                .isInstanceOf(IllegalApprovalTransitionException.class);
    }

    @Test
    void revoke_FromApproved_MovesToRevoked() {
        Action action = asUser(alice, this::seedAction);
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));
        asUser(alice, () -> approvalService.approve(action.getId()));

        Action revoked = asUser(alice, () -> approvalService.revoke(action.getId()));

        assertThat(revoked.getApprovalState()).isEqualTo(ApprovalState.REVOKED);
        assertThat(revoked.getApprovedSnapshotHash()).isNull();
    }

    @Test
    void revoke_FromPendingApproval_MovesToRevoked() {
        Action action = asUser(alice, this::seedAction);
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));

        Action revoked = asUser(alice, () -> approvalService.revoke(action.getId()));

        assertThat(revoked.getApprovalState()).isEqualTo(ApprovalState.REVOKED);
    }

    @Test
    void revoke_FromDraft_IsIllegalTransition() {
        Action action = asUser(alice, this::seedAction);

        assertThatThrownBy(() -> asUser(alice, () -> approvalService.revoke(action.getId())))
                .isInstanceOf(IllegalApprovalTransitionException.class);
    }

    @Test
    void editAction_OfApprovedAction_ResetsToDraftAndClearsHash() {
        Action action = asUser(alice, this::seedAction);
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));
        Action approved = asUser(alice, () -> approvalService.approve(action.getId()));
        assertThat(approved.getApprovalState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(approved.getApprovedSnapshotHash()).isNotBlank();

        Action edited = asUser(alice, () -> actionService.editAction(action.getId(), new EditActionInput(
                "restart service", "now with an extra flag", true,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl"),
                        new ArgTokenInput(TokenKind.LITERAL, "reload"),
                        new ArgTokenInput(TokenKind.PARAM, "svc")),
                List.of(new ParamDefInput("svc", ParamKind.ALLOWED_SET, null, null, null,
                        List.of("nginx", "docker"))))));

        assertThat(edited.getApprovalState()).isEqualTo(ApprovalState.DRAFT);
        assertThat(edited.getApprovedSnapshotHash()).isNull();
        assertThat(edited.getApprovedByUserId()).isNull();
        assertThat(edited.getApprovedAt()).isNull();
    }

    @Test
    void approve_AnotherUsersAction_Is404() {
        Action action = asUser(alice, this::seedAction);
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));

        assertThatThrownBy(() -> asUser(bob, () -> approvalService.approve(action.getId())))
                .isInstanceOf(ActionNotFoundException.class);
    }

    /** Registers a machine, a recipe, and a draft action for the current user. */
    private Action seedAction() {
        Machine machine = machineService.register(new RegisterMachineInput("host", 22, "root"));
        Recipe recipe = recipeService.create(new CreateRecipeInput(
                machine.getId(), "nginx", "nginx service ops", RecipeType.NGINX));
        return actionService.addAction(new AddActionInput(
                recipe.getId(), "restart nginx", "restart a service", true,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl"),
                        new ArgTokenInput(TokenKind.LITERAL, "restart"),
                        new ArgTokenInput(TokenKind.PARAM, "svc")),
                List.of(new ParamDefInput("svc", ParamKind.ALLOWED_SET, null, null, null,
                        List.of("nginx", "docker", "mysql")))));
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
