package com.iskeru.computeadmin.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.repository.ActionRepository;
import com.iskeru.computeadmin.recipe.service.ActionNotApprovedException;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.recipe.service.ParamValidationException;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.RecipeService.CreateRecipeInput;
import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.model.RunStatus;
import com.iskeru.computeadmin.run.repository.RunRepository;
import com.iskeru.computeadmin.run.service.ActionModifiedException;
import com.iskeru.computeadmin.run.service.RunOutputHub;
import com.iskeru.computeadmin.run.service.RunService;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The run gate (spec-005). Refuses a non-APPROVED action (409), refuses an action
 * mutated since approval (409, defending the spec-004 binding at run time), refuses
 * invalid params (400), and — on the happy path — records the exact resolved argv
 * and the captured exit code on the append-only run.
 *
 * <p>A {@link SyncTaskExecutor} runs the async task inline so the assertion sees a
 * completed run; a fake {@link SshExecutor} stands in for the real transport. The
 * class is {@code NOT_SUPPORTED} (non-transactional) so the service's own
 * transaction really commits and the after-commit dispatch fires.
 *
 * <p>spec-005.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({RunService.class, RunOutputHub.class, MachineService.class, ActionService.class,
        RecipeService.class, ApprovalService.class, ParamBinder.class,
        RunServiceTest.TestBeans.class})
class RunServiceTest {

    static class TestBeans {

        @Bean("runExecutor")
        TaskExecutor runExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(2);
            executor.setDaemon(true);
            executor.initialize();
            return executor;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SshExecutor sshExecutor() {
            return new SshExecutor() {
                @Override
                public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
                    return new ExecResult(0, "ok\n", "");
                }

                @Override
                public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
                    sink.onStdout("ok\n");
                    sink.onComplete(0);
                }
            };
        }
    }

    @Autowired
    private RunService runService;

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

    private record Seed(String machineId, String actionId) {
    }

    @Test
    void run_NonApprovedAction_IsRefused() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedAction(false));

        assertThatThrownBy(() -> asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "nginx"))))
                .isInstanceOf(ActionNotApprovedException.class);
    }

    @Test
    void run_ActionMutatedSinceApproval_IsRefused() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedAction(true));

        // Mutate a hashed field (sudo) directly, keeping approvalState=APPROVED and the
        // stale approvedSnapshotHash — so the live hash no longer matches.
        Action action = actions.findById(seed.actionId()).orElseThrow();
        action.setSudo(!action.isSudo());
        actions.save(action);

        assertThatThrownBy(() -> asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "nginx"))))
                .isInstanceOf(ActionModifiedException.class);
    }

    @Test
    void run_InvalidParams_IsRefused() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedAction(true));

        assertThatThrownBy(() -> asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "evil"))))
                .isInstanceOf(ParamValidationException.class);
    }

    // NOT_SUPPORTED: run non-transactionally so the service's own transaction really
    // commits and the after-commit dispatch fires (inline, via the SyncTaskExecutor).
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void run_HappyPath_RecordsResolvedArgvAndExitCode() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedAction(true));

        Run queued = asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "nginx")));

        Run finished = awaitTerminal(queued.getId());
        assertThat(finished.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(finished.getExitCode()).isZero();
        assertThat(finished.getResolvedArgvJson()).contains("systemctl", "restart", "nginx");
        assertThat(finished.getStdout()).contains("ok");
        assertThat(finished.getApprovedSnapshotHash()).isNotBlank();
        assertThat(finished.getCallerUserId()).isEqualTo(user.getId());
    }

    /** Polls the run until it reaches a terminal state (or a short timeout). */
    private Run awaitTerminal(String runId) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Run run = runs.findById(runId).orElseThrow();
            if (run.getStatus() == RunStatus.DONE || run.getStatus() == RunStatus.FAILED) {
                return run;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return runs.findById(runId).orElseThrow();
    }

    // --- fixtures -----------------------------------------------------------

    /** Registers a machine + recipe + action; approves it when {@code approve}. */
    private Seed seedAction(boolean approve) {
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
        if (approve) {
            approvalService.submitForApproval(action.getId());
            approvalService.approve(action.getId());
        }
        return new Seed(machine.getId(), action.getId());
    }

    private AppUser saveUser() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setGoogleSub("dev|" + email);
        user.setName("user");
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }
}
