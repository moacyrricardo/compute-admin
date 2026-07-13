package com.iskeru.computeadmin.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.Via;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.repository.ActionRepository;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.ScriptPinService;
import com.iskeru.computeadmin.ssh.StubSshExecutor;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.RecipeService.CreateRecipeInput;
import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.model.RunStatus;
import com.iskeru.computeadmin.run.repository.RunRepository;
import com.iskeru.computeadmin.run.service.RunReconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boot reconciler (spec-016). On startup every run left {@code QUEUED}/
 * {@code RUNNING} by the previous, now-dead process is unconditionally marked
 * terminal {@code INTERRUPTED} — with {@code finishedAt} set, {@code exitCode = -1},
 * and the sentinel note appended to stderr — while an already-terminal run is left
 * untouched.
 *
 * <p>Isolated per spec-013: each test seeds a unique owner and cleans its rows in
 * {@code @AfterEach}, since the in-memory test DB is shared ({@code DB_CLOSE_DELAY=-1}).
 *
 * <p>spec-016.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({RunReconciler.class, MachineService.class, ActionService.class,
        RecipeService.class, ApprovalService.class, ScriptPinService.class,
        StubSshExecutor.class, ParamBinder.class, RunReconcilerTest.TestBeans.class})
class RunReconcilerTest {

    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private RunReconciler reconciler;

    @Autowired
    private MachineService machineService;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private ActionService actionService;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ActionRepository actions;

    @Autowired
    private RunRepository runs;

    @Autowired
    private AppUserRepository users;

    private final List<String> seededRunIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : seededRunIds) {
            runs.findById(id).ifPresent(runs::delete);
        }
        seededRunIds.clear();
    }

    @Test
    void reconcile_MarksQueuedAndRunningInterrupted_LeavesTerminalUntouched() {
        AppUser user = saveUser();
        Action action = asUser(user, this::seedApprovedAction);

        String queuedId = saveRun(user, action, RunStatus.QUEUED, null, null);
        String runningId = saveRun(user, action, RunStatus.RUNNING, null, "partial output\n");
        String doneId = saveRun(user, action, RunStatus.DONE, 0, "done\n");
        String failedId = saveRun(user, action, RunStatus.FAILED, 2, "boom\n");

        int reconciled = reconciler.reconcile();

        assertThat(reconciled).isEqualTo(2);

        Run queued = runs.findById(queuedId).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(queued.getFinishedAt()).isNotNull();
        assertThat(queued.getExitCode()).isEqualTo(-1);
        assertThat(queued.getStderr()).contains(RunReconciler.SENTINEL);

        Run running = runs.findById(runningId).orElseThrow();
        assertThat(running.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(running.getFinishedAt()).isNotNull();
        assertThat(running.getExitCode()).isEqualTo(-1);
        // The sentinel is appended to any output already captured, not overwriting it.
        assertThat(running.getStderr()).contains("partial output").contains(RunReconciler.SENTINEL);

        // Already-terminal rows are left exactly as they were.
        Run done = runs.findById(doneId).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(done.getExitCode()).isEqualTo(0);
        assertThat(done.getStderr()).doesNotContain(RunReconciler.SENTINEL);

        Run failed = runs.findById(failedId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getExitCode()).isEqualTo(2);
        assertThat(failed.getStderr()).doesNotContain(RunReconciler.SENTINEL);
    }

    @Test
    void reconcile_NoOrphans_ReturnsZero() {
        AppUser user = saveUser();
        Action action = asUser(user, this::seedApprovedAction);
        saveRun(user, action, RunStatus.DONE, 0, "done\n");

        assertThat(reconciler.reconcile()).isZero();
    }

    // --- fixtures -----------------------------------------------------------

    private String saveRun(AppUser user, Action action, RunStatus status, Integer exitCode, String stderr) {
        Run run = new Run();
        run.setAction(action);
        run.setMachine(action.getRecipe().getMachine());
        run.setCallerUserId(user.getId());
        run.setVia(Via.UI);
        run.setResolvedArgvJson("[]");
        run.setApprovedSnapshotHash(action.getApprovedSnapshotHash());
        run.setStatus(status);
        run.setExitCode(exitCode);
        run.setStderr(stderr);
        if (status == RunStatus.RUNNING) {
            run.setStartedAt(Instant.now());
        }
        String id = runs.save(run).getId();
        seededRunIds.add(id);
        return id;
    }

    private Action seedApprovedAction() {
        Machine machine = machineService.register(new RegisterMachineInput("host", 22, "root"));
        Recipe recipe = recipeService.create(new CreateRecipeInput(
                machine.getId(), "nginx", "nginx service ops", RecipeType.NGINX));
        Action action = actionService.addAction(new AddActionInput(
                recipe.getId(), "restart nginx", "restart a service", true,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl"),
                        new ArgTokenInput(TokenKind.LITERAL, "restart"),
                        new ArgTokenInput(TokenKind.PARAM, "svc")),
                List.of(new ParamDefInput("svc", ParamKind.ALLOWED_SET, null, null, null,
                        List.of("nginx", "docker", "mysql")))));
        approvalService.submitForApproval(action.getId());
        approvalService.approve(action.getId());
        return actions.findById(action.getId()).orElseThrow();
    }

    private AppUser saveUser() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
        user.setName("user");
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }
}
