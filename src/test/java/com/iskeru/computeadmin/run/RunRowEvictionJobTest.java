package com.iskeru.computeadmin.run;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.Via;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.repository.ActionRepository;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.RecipeService.CreateRecipeInput;
import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.model.RunStatus;
import com.iskeru.computeadmin.run.repository.RunRepository;
import com.iskeru.computeadmin.run.service.RunRowEvictionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run-row pruning (spec-022, extending spec-013's in-memory eviction). Verifies the
 * two bounds on the append-only {@code run} log: terminal top-level rows past the
 * retention window are deleted <em>with their children</em>; a {@code QUEUED}/
 * {@code RUNNING} row is never deleted; and beyond the per-action cap the oldest
 * terminal rows are trimmed. Drives the {@code prune(asOf)} seam directly.
 *
 * <p>The per-action cap is pinned to 2 for this slice.
 *
 * <p>spec-022.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "ca.run.rows-per-action-max=2")
@Import({RunRowEvictionJob.class, MachineService.class, RecipeService.class,
        ActionService.class, ApprovalService.class, ParamBinder.class})
class RunRowEvictionJobTest {

    @Autowired
    private RunRowEvictionJob job;

    @Autowired
    private MachineService machineService;

    @Autowired
    private RecipeService recipeService;

    @Autowired
    private ActionService actionService;

    @Autowired
    private ActionRepository actions;

    @Autowired
    private RunRepository runs;

    @Autowired
    private AppUserRepository users;

    @Test
    void prune_TerminalRowsPastRetention_AreDeletedWithChildren() {
        AppUser user = saveUser();
        Instant now = Instant.now();
        String[] ids = new String[4]; // [oldParent, oldChild, freshParent, freshChild]
        String actionId = asUser(user, () -> {
            Action action = seedAction(user);
            // An old fan-out poll (parent + child), both terminal 48h ago → prunable.
            Run oldParent = saveRun(action, RunStatus.DONE, now.minus(Duration.ofHours(48)), null, null);
            Run oldChild = saveRun(action, RunStatus.DONE, now.minus(Duration.ofHours(48)), oldParent.getId(), "orders");
            // A recent poll → survives.
            Run freshParent = saveRun(action, RunStatus.DONE, now, null, null);
            Run freshChild = saveRun(action, RunStatus.DONE, now, freshParent.getId(), "orders");
            ids[0] = oldParent.getId();
            ids[1] = oldChild.getId();
            ids[2] = freshParent.getId();
            ids[3] = freshChild.getId();
            return action.getId();
        });

        job.prune(now);

        List<String> remaining = runsForAction(actionId).stream().map(Run::getId).toList();
        // The old parent AND its child are gone; the fresh poll survives intact.
        assertThat(remaining).containsExactlyInAnyOrder(ids[2], ids[3]);
    }

    @Test
    void prune_NonTerminalRow_IsNeverDeleted() {
        AppUser user = saveUser();
        Instant now = Instant.now();
        String actionId = asUser(user, () -> {
            Action action = seedAction(user);
            // Old but non-terminal: must survive regardless of age.
            saveRun(action, RunStatus.QUEUED, now.minus(Duration.ofDays(30)), null, null);
            saveRun(action, RunStatus.RUNNING, now.minus(Duration.ofDays(30)), null, null);
            return action.getId();
        });

        job.prune(now);

        List<Run> mine = runsForAction(actionId);
        assertThat(mine).hasSize(2);
        assertThat(mine).extracting(Run::getStatus)
                .containsExactlyInAnyOrder(RunStatus.QUEUED, RunStatus.RUNNING);
    }

    @Test
    void prune_PerActionCap_TrimsOldestTerminalTopLevelRows() {
        AppUser user = saveUser();
        Instant now = Instant.now();
        String actionId = asUser(user, () -> {
            Action action = seedAction(user);
            // Four recent terminal polls (kept by retention) for one action; the cap is
            // 2, so the oldest two parents (and their children) are trimmed.
            for (int i = 0; i < 4; i++) {
                Instant createdAt = now.minus(Duration.ofMinutes(4 - i)); // i=0 oldest
                Run parent = saveRunAt(action, RunStatus.DONE, createdAt, now, null, null);
                saveRunAt(action, RunStatus.DONE, createdAt, now, parent.getId(), "orders");
            }
            return action.getId();
        });

        job.prune(now);

        // Keep the newest 2 parents + their 2 children = 4 rows.
        List<Run> mine = runsForAction(actionId);
        assertThat(mine).hasSize(4);
        assertThat(mine.stream().filter(r -> r.getParentRunId() == null).toList()).hasSize(2);
    }

    // --- fixtures -----------------------------------------------------------

    /** Runs belonging to one action (isolates assertions from any leaked committed rows). */
    private List<Run> runsForAction(String actionId) {
        return runs.findAll().stream()
                .filter(r -> r.getAction().getId().equals(actionId))
                .toList();
    }

    private Action seedAction(AppUser user) {
        Machine machine = machineService.register(new RegisterMachineInput("host", 22, "root"));
        Recipe recipe = recipeService.create(new CreateRecipeInput(
                machine.getId(), "monitor", "monitors", RecipeType.MONITOR));
        return actionService.addAction(new AddActionInput(
                recipe.getId(), "vitals", "host vitals", false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "uptime")),
                List.of()));
    }

    private Run saveRun(Action action, RunStatus status, Instant finishedAt, String parentId, String label) {
        return saveRunAt(action, status, Instant.now(), finishedAt, parentId, label);
    }

    private Run saveRunAt(Action action, RunStatus status, Instant createdAt, Instant finishedAt,
                          String parentId, String label) {
        Run run = new Run();
        run.setAction(action);
        run.setMachine(action.getRecipe().getMachine());
        run.setCallerUserId(action.getRecipe().getMachine().getOwner().getId());
        run.setVia(Via.UI);
        run.setResolvedArgvJson("[]");
        run.setApprovedSnapshotHash("hash");
        run.setStatus(status);
        run.setCreatedAt(createdAt);
        run.setFinishedAt(finishedAt);
        run.setParentRunId(parentId);
        run.setAppLabel(label);
        return runs.saveAndFlush(run);
    }

    private AppUser saveUser() {
        AppUser user = new AppUser();
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
        user.setName("user");
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }
}
