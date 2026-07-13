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
import com.iskeru.computeadmin.recipe.service.ScriptPinService;
import com.iskeru.computeadmin.recipe.service.ScriptUnreadableException;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
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
import java.util.concurrent.CopyOnWriteArrayList;
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
@Import({ApprovalService.class, ActionService.class, RecipeService.class, MachineService.class,
        ScriptPinService.class, ApprovalServiceTest.FakeSshConfig.class})
class ApprovalServiceTest {

    /** A valid 64-char hex digest a {@code sha256sum} probe reports by default. */
    private static final String SCRIPT_HASH =
            "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc1230000";

    @TestConfiguration
    static class FakeSshConfig {
        @Bean
        FakeSsh fakeSshExecutor() {
            return new FakeSsh();
        }
    }

    /**
     * Records every argv handed to {@code exec} (so a test can assert the {@code
     * sha256sum} probe received the path as a single argument) and returns a
     * configurable {@link ExecResult} (so a test can simulate an unreadable script).
     * spec-015.
     */
    static final class FakeSsh implements SshExecutor {
        final List<List<String>> execArgv = new CopyOnWriteArrayList<>();
        volatile ExecResult response = new ExecResult(0, SCRIPT_HASH + "  /path\n", "");

        @Override
        public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
            execArgv.add(List.copyOf(argv));
            return response;
        }

        @Override
        public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
            sink.onComplete(0);
        }
    }

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

    @Autowired
    private FakeSsh ssh;

    private AppUser alice;
    private AppUser bob;

    @BeforeEach
    void seedUsers() {
        alice = saveUser("alice@example.com");
        bob = saveUser("bob@example.com");
        ssh.execArgv.clear();
        ssh.response = new ExecResult(0, SCRIPT_HASH + "  /path\n", "");
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

    // --- content-pinning of CUSTOM actions (spec-015) -----------------------

    @Test
    void approve_CustomAction_PinsScriptHash() {
        Action action = asUser(alice, () -> seedCustomAction("/opt/app/run.sh"));
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));

        Action approved = asUser(alice, () -> approvalService.approve(action.getId()));

        assertThat(approved.getApprovalState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(approved.getApprovedScriptHash()).isEqualTo(SCRIPT_HASH);
        assertThat(ssh.execArgv).containsExactly(List.of("sha256sum", "/opt/app/run.sh"));
    }

    @Test
    void approve_NonCustomAction_LeavesScriptHashNull() {
        Action action = asUser(alice, this::seedAction); // an NGINX action
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));

        Action approved = asUser(alice, () -> approvalService.approve(action.getId()));

        assertThat(approved.getApprovedScriptHash()).isNull();
        // A non-CUSTOM action is never probed.
        assertThat(ssh.execArgv).isEmpty();
    }

    @Test
    void approve_ScriptUnreadable_Refuses() {
        Action action = asUser(alice, () -> seedCustomAction("/opt/app/run.sh"));
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));
        // sha256sum exits non-zero: the script is missing / unreadable / sha256sum absent.
        ssh.response = new ExecResult(1, "", "sha256sum: /opt/app/run.sh: No such file or directory");

        assertThatThrownBy(() -> asUser(alice, () -> approvalService.approve(action.getId())))
                .isInstanceOf(ScriptUnreadableException.class);

        // Refused without mutating approval state — the action is still PENDING_APPROVAL
        // and unpinned (the probe runs before any state change).
        Action after = asUser(alice, () -> actionService.requireAction(action.getId()));
        assertThat(after.getApprovalState()).isEqualTo(ApprovalState.PENDING_APPROVAL);
        assertThat(after.getApprovedScriptHash()).isNull();
    }

    @Test
    void approve_PathWithSpaces_ProbesSingleArgument() {
        Action action = asUser(alice, () -> seedCustomAction("/path with space/run.sh"));
        asUser(alice, () -> approvalService.submitForApproval(action.getId()));

        asUser(alice, () -> approvalService.approve(action.getId()));

        // The path is one discrete argv element; POSIX quoting is the SSH adapter's job.
        assertThat(ssh.execArgv).containsExactly(List.of("sha256sum", "/path with space/run.sh"));
    }

    /** Registers a machine and a CUSTOM recipe/action wrapping {@code scriptPath}. */
    private Action seedCustomAction(String scriptPath) {
        Machine machine = machineService.register(new RegisterMachineInput("host", 22, "root"));
        Recipe recipe = recipeService.create(new CreateRecipeInput(
                machine.getId(), "custom", "custom commands", RecipeType.CUSTOM));
        return actionService.addAction(new AddActionInput(
                recipe.getId(), "run script", "runs the wrapped script", false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, scriptPath)),
                List.of()));
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
        user.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
        user.setName(email.substring(0, email.indexOf('@')));
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }
}
