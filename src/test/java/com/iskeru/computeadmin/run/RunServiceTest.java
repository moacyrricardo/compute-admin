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
import com.iskeru.computeadmin.recipe.service.ScriptPinService;
import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.model.RunStatus;
import com.iskeru.computeadmin.run.repository.RunRepository;
import com.iskeru.computeadmin.run.service.ActionModifiedException;
import com.iskeru.computeadmin.run.service.RunNotFoundException;
import com.iskeru.computeadmin.run.service.RunOutputHub;
import com.iskeru.computeadmin.run.service.RunService;
import com.iskeru.computeadmin.run.service.ScriptModifiedException;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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
        RecipeService.class, ApprovalService.class, ParamBinder.class, ScriptPinService.class,
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
        RecordingSshExecutor sshExecutor() {
            return new RecordingSshExecutor();
        }
    }

    /**
     * A fake transport that records every argv it is handed, so a fan-out test can
     * assert it dispatched the fixed template once per item as DISCRETE argv lists —
     * never a single looping/{@code &&}/{@code ;} shell line (the S4 regression).
     */
    static final class RecordingSshExecutor implements SshExecutor {

        final List<List<String>> argvCalls = new CopyOnWriteArrayList<>();

        /**
         * When set, the cancellable {@code execStreaming} <em>blocks</em> until
         * {@link #cancel(String)} releases it — simulating a follow-mode ({@code -f})
         * stream that never exits on its own, so a cancel test can prove the channel is
         * stopped. Off by default, so every other test runs the fast one-shot path.
         */
        volatile boolean blockUntilCancelled = false;
        final Map<String, CountDownLatch> latches = new ConcurrentHashMap<>();
        final Set<String> cancelled = ConcurrentHashMap.newKeySet();

        /**
         * The digest a {@code sha256sum} probe reports (spec-015). A pinned CUSTOM action
         * is content-hashed at approval and re-verified at run against this value; a test
         * changes it between approve and run to simulate a post-approval byte-swap.
         */
        volatile String scriptHash = "1111111111111111111111111111111111111111111111111111111111111111";

        @Override
        public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
            argvCalls.add(List.copyOf(argv));
            if (!argv.isEmpty() && "sha256sum".equals(argv.get(0))) {
                String path = argv.size() > 1 ? argv.get(1) : "";
                return new ExecResult(0, scriptHash + "  " + path + "\n", "");
            }
            return new ExecResult(0, "ok\n", "");
        }

        @Override
        public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
            argvCalls.add(List.copyOf(argv));
            sink.onStdout("ok\n");
            sink.onComplete(0);
        }

        @Override
        public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink,
                                  String cancelKey) {
            if (!blockUntilCancelled) {
                execStreaming(target, argv, sudo, sink);
                return;
            }
            argvCalls.add(List.copyOf(argv));
            CountDownLatch latch = new CountDownLatch(1);
            latches.put(cancelKey, latch);
            sink.onStdout("streaming...\n");
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // A cancelled follow-mode channel returns with no clean exit code (-1).
            sink.onComplete(-1);
        }

        @Override
        public boolean cancel(String cancelKey) {
            cancelled.add(cancelKey);
            CountDownLatch latch = latches.remove(cancelKey);
            if (latch == null) {
                return false;
            }
            latch.countDown();
            return true;
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
    private RunOutputHub hub;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private RecordingSshExecutor ssh;

    private record Seed(String machineId, String actionId) {
    }

    // The recording SSH fake is a shared singleton; reset its capture before each test
    // so one test's dispatches never bleed into another's argv assertions.
    @BeforeEach
    void resetRecordedArgv() {
        ssh.argvCalls.clear();
        ssh.blockUntilCancelled = false;
        ssh.latches.clear();
        ssh.cancelled.clear();
        ssh.scriptHash = "1111111111111111111111111111111111111111111111111111111111111111";
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

    // NOT_SUPPORTED: commit the run so the after-commit dispatch fires and the run
    // executes to terminal, populating (then evictable) the hub channel.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void subscribeAfterEviction_ReplaysPersistedOutput() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedAction(true));

        Run queued = asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "nginx")));
        Run finished = awaitTerminal(queued.getId());
        assertThat(finished.getStatus()).isEqualTo(RunStatus.DONE);

        // Force the hub to drop the finished run's channel via the asOf seam.
        hub.evict(Instant.now().plus(Duration.ofDays(1)));

        Recorder recorder = new Recorder();
        asUser(user, () -> {
            runService.subscribeToOutput(queued.getId(), recorder);
            return null;
        });

        // With the channel gone, the subscriber is served from the persisted run:
        // stdout chunk, the terminal exit event, then onComplete.
        await().atMost(2, TimeUnit.SECONDS).until(recorder::completed);
        assertThat(recorder.stream(RunOutputHub.OutputEvent.STDOUT)).contains("ok");
        assertThat(recorder.stream(RunOutputHub.OutputEvent.EXIT)).contains("0");
    }

    @Test
    void subscribeToInterruptedRun_ReplaysPersistedOutput_DoesNotHang() {
        AppUser user = saveUser();
        Recorder recorder = new Recorder();

        asUser(user, () -> {
            Seed seed = seedAction(true);
            Action action = actions.findById(seed.actionId()).orElseThrow();
            // A run the boot reconciler left INTERRUPTED: terminal, exitCode -1, with
            // captured output but NO live hub channel (the owning process died).
            Run run = new Run();
            run.setAction(action);
            run.setMachine(action.getRecipe().getMachine());
            run.setCallerUserId(user.getId());
            run.setVia(Via.UI);
            run.setResolvedArgvJson("[]");
            run.setApprovedSnapshotHash(action.getApprovedSnapshotHash());
            run.setStatus(RunStatus.INTERRUPTED);
            run.setExitCode(-1);
            run.setStdout("partial\n");
            run.setStderr("Run abandoned by a server shutdown; the remote command's actual outcome is unknown.");
            Run saved = runs.save(run);

            // INTERRUPTED is terminal, so this must replay the persisted row and
            // complete — never create a fresh live channel that would hang forever.
            runService.subscribeToOutput(saved.getId(), recorder);
            return null;
        });

        await().atMost(2, TimeUnit.SECONDS).until(recorder::completed);
        assertThat(recorder.stream(RunOutputHub.OutputEvent.STDOUT)).contains("partial");
        assertThat(recorder.stream(RunOutputHub.OutputEvent.EXIT)).contains("-1");
    }

    @Test
    void subscribeToQueuedRun_AttachesLive() {
        AppUser user = saveUser();
        Recorder recorder = new Recorder();

        asUser(user, () -> {
            Seed seed = seedAction(true);
            // A QUEUED run persisted directly (no async dispatch) stays live.
            Action action = actions.findById(seed.actionId()).orElseThrow();
            Run run = new Run();
            run.setAction(action);
            run.setMachine(action.getRecipe().getMachine());
            run.setCallerUserId(user.getId());
            run.setVia(Via.UI);
            run.setResolvedArgvJson("[]");
            run.setApprovedSnapshotHash(action.getApprovedSnapshotHash());
            run.setStatus(RunStatus.QUEUED);
            Run saved = runs.save(run);

            // A live (QUEUED) run attaches to the hub, not the persisted fallback.
            runService.subscribeToOutput(saved.getId(), recorder);
            hub.publish(saved.getId(), new RunOutputHub.OutputEvent(RunOutputHub.OutputEvent.STDOUT, "live\n"));
            hub.complete(saved.getId(), 0);
            return null;
        });

        await().atMost(2, TimeUnit.SECONDS).until(recorder::completed);
        assertThat(recorder.stream(RunOutputHub.OutputEvent.STDOUT)).contains("live");
        assertThat(recorder.stream(RunOutputHub.OutputEvent.EXIT)).contains("0");
    }

    // --- run cancellation (spec-026) ----------------------------------------

    // NOT_SUPPORTED: commit so the after-commit dispatch fires and the run actually
    // reaches the blocking (follow-mode) executor, then cancel it.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancel_RunningRun_ClosesChannel_MarksStopped_CompletesHub() {
        ssh.blockUntilCancelled = true;
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedAction(true));

        Run queued = asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "nginx")));
        String runId = queued.getId();

        // The blocking executor holds the run in RUNNING until cancel releases it.
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> runs.findById(runId).orElseThrow().getStatus() == RunStatus.RUNNING);

        // A live subscriber must be completed cleanly when the run is cancelled.
        Recorder recorder = new Recorder();
        asUser(user, () -> {
            runService.subscribeToOutput(runId, recorder);
            return null;
        });

        Run stopped = asUser(user, () -> runService.cancel(runId));
        assertThat(stopped.getStatus()).isEqualTo(RunStatus.STOPPED);

        // The transport was signalled and the run stays terminal STOPPED — the returning
        // execStreaming's finish() must not override it back to DONE/FAILED.
        await().atMost(5, TimeUnit.SECONDS).until(() -> ssh.cancelled.contains(runId));
        await().atMost(5, TimeUnit.SECONDS).until(recorder::completed);
        Run finished = runs.findById(runId).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(RunStatus.STOPPED);
        assertThat(finished.getExitCode()).isEqualTo(-1);
        assertThat(finished.getFinishedAt()).isNotNull();
    }

    @Test
    void cancel_NotOwnedRun_Is404() {
        AppUser owner = saveUser();
        AppUser other = saveUser();
        Seed seed = asUser(owner, () -> seedAction(true));
        // Default (rolled-back) tx: the after-commit dispatch never fires, so the run
        // stays QUEUED — exactly the live run another user must not be able to cancel.
        Run queued = asUser(owner, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "nginx")));

        assertThatThrownBy(() -> asUser(other, () -> runService.cancel(queued.getId())))
                .isInstanceOf(RunNotFoundException.class);
    }

    // NOT_SUPPORTED: let the (non-blocking) run finish DONE, then prove cancel is a no-op.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancel_TerminalRun_IsNoOp() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedAction(true));

        Run queued = asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "nginx")));
        Run done = awaitTerminal(queued.getId());
        assertThat(done.getStatus()).isEqualTo(RunStatus.DONE);

        Run after = asUser(user, () -> runService.cancel(queued.getId()));
        assertThat(after.getStatus()).isEqualTo(RunStatus.DONE);
        // A terminal run is never signalled to the transport.
        assertThat(ssh.cancelled).doesNotContain(queued.getId());
    }

    // --- fan-out (spec-022) -------------------------------------------------

    // NOT_SUPPORTED: commit so the after-commit dispatch fires and every child runs.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void run_FanOutAction_RunsFixedTemplatePerItem_NeverAShellLoop() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedFanOutAction(true));
        String items = "[{\"appName\":\"orders\",\"port\":8080},"
                + "{\"appName\":\"billing\",\"port\":9090},"
                + "{\"appName\":\"web\",\"port\":8000}]";

        Run parent = asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("apps", items)));

        // The parent aggregates its children: DONE once all three children are DONE.
        Run finishedParent = awaitTerminal(parent.getId());
        assertThat(finishedParent.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(finishedParent.getParentRunId()).isNull();

        List<Run> children = runs.findByParentRunId(parent.getId());
        assertThat(children).hasSize(3);
        assertThat(children).allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(RunStatus.DONE));
        assertThat(children).extracting(Run::getAppLabel)
                .containsExactlyInAnyOrder("orders", "billing", "web");

        // The direct S4 regression: THREE fixed-template argv lists, each a discrete
        // argv — never one looping/&&/;/| shell line.
        assertThat(ssh.argvCalls).hasSize(3);
        assertThat(ssh.argvCalls).allSatisfy(argv -> {
            assertThat(argv).hasSize(3);
            assertThat(argv.get(0)).isEqualTo("probe");
            assertThat(argv).noneMatch(a -> a.contains("&&") || a.contains(";")
                    || a.contains("|") || a.contains(" "));
        });
        // The port bound per item is exactly the item's validated scalar.
        assertThat(ssh.argvCalls).extracting(argv -> argv.get(2))
                .containsExactlyInAnyOrder("8080", "9090", "8000");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void childRuns_ListsFanOutChildrenByAppLabel_ScalarHasNone() {
        AppUser user = saveUser();
        Seed fanOut = asUser(user, () -> seedFanOutAction(true));
        String items = "[{\"appName\":\"orders\",\"port\":8080},"
                + "{\"appName\":\"billing\",\"port\":9090}]";

        Run parent = asUser(user, () -> runService.run(fanOut.machineId(), fanOut.actionId(), Map.of("apps", items)));
        awaitTerminal(parent.getId());

        // The fleet poll (spec-029) lists a parent's children to attribute each app's
        // probe output to its card — one child per (app-name, port) item, keyed by label.
        List<Run> children = asUser(user, () -> runService.childRuns(parent.getId()));
        assertThat(children).extracting(Run::getAppLabel)
                .containsExactlyInAnyOrder("orders", "billing");

        // A scalar run is not a fan-out: no children (seeded under a fresh user, since
        // both seeds register the same machine host and it is unique per owner).
        AppUser scalarUser = saveUser();
        Seed scalar = asUser(scalarUser, () -> seedAction(true));
        Run scalarRun = asUser(scalarUser, () -> runService.run(scalar.machineId(), scalar.actionId(), Map.of("svc", "nginx")));
        awaitTerminal(scalarRun.getId());
        assertThat(asUser(scalarUser, () -> runService.childRuns(scalarRun.getId()))).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void childRuns_OfAnotherUsersParent_Is404() {
        AppUser owner = saveUser();
        AppUser other = saveUser();
        Seed fanOut = asUser(owner, () -> seedFanOutAction(true));
        Run parent = asUser(owner,
                () -> runService.run(fanOut.machineId(), fanOut.actionId(),
                        Map.of("apps", "[{\"appName\":\"orders\",\"port\":8080}]")));
        awaitTerminal(parent.getId());

        assertThatThrownBy(() -> asUser(other, () -> runService.childRuns(parent.getId())))
                .isInstanceOf(RunNotFoundException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void run_FanOutWithOneInvalidItem_FailsWholeRun_NothingDispatched() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedFanOutAction(true));
        // The second item's app-name carries a space — outside the fixed charset.
        String items = "[{\"appName\":\"orders\",\"port\":8080},"
                + "{\"appName\":\"bad name\",\"port\":9090}]";

        long before = runs.count();
        assertThatThrownBy(() -> asUser(user,
                () -> runService.run(seed.machineId(), seed.actionId(), Map.of("apps", items))))
                .isInstanceOf(ParamValidationException.class);

        // Nothing dispatched and nothing persisted: one bad item fails the whole run
        // before any child (or the parent) is saved.
        assertThat(ssh.argvCalls).isEmpty();
        assertThat(runs.count()).isEqualTo(before);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void run_ScalarAction_HasNoParentOrLabel_UnchangedByFanOut() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedAction(true));

        Run queued = asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "nginx")));
        Run finished = awaitTerminal(queued.getId());

        assertThat(finished.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(finished.getParentRunId()).isNull();
        assertThat(finished.getAppLabel()).isNull();
        // A scalar action is a fan-out of size 1: one dispatch of the fixed argv.
        assertThat(ssh.argvCalls).hasSize(1);
        assertThat(ssh.argvCalls.get(0)).containsSubsequence("systemctl", "restart", "nginx");
    }

    // --- content-pinning of CUSTOM actions (spec-015) -----------------------

    // NOT_SUPPORTED so the service's own transaction commits and the after-commit dispatch
    // fires: the run-time re-probe matches the pinned hash, so the run proceeds to DONE.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void run_ScriptUnchanged_Runs() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedCustomAction("/opt/app/run.sh"));
        ssh.argvCalls.clear(); // drop the approve-time probe; capture only the run phase.

        Run queued = asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of()));

        Run finished = awaitTerminal(queued.getId());
        assertThat(finished.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(finished.getExitCode()).isZero();
        // The run re-probed the script (same hash) and then dispatched the script itself.
        assertThat(ssh.argvCalls).contains(List.of("sha256sum", "/opt/app/run.sh"));
        assertThat(ssh.argvCalls).contains(List.of("/opt/app/run.sh"));
    }

    @Test
    void run_ScriptContentDrifted_Throws409() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedCustomAction("/opt/app/run.sh"));
        // The on-box script is swapped after approval: sha256sum now reports a different hash.
        ssh.scriptHash = "2222222222222222222222222222222222222222222222222222222222222222";
        ssh.argvCalls.clear();

        assertThatThrownBy(() -> asUser(user,
                () -> runService.run(seed.machineId(), seed.actionId(), Map.of())))
                .isInstanceOf(ScriptModifiedException.class);

        // The re-probe happened, but the drifted script was never dispatched.
        assertThat(ssh.argvCalls).containsExactly(List.of("sha256sum", "/opt/app/run.sh"));
    }

    @Test
    void run_NonPinnedAction_SkipsProbe() {
        AppUser user = saveUser();
        // A non-CUSTOM approved action carries no approvedScriptHash, so the content gate
        // is skipped entirely — no sha256sum probe is attempted at run time.
        Seed seed = asUser(user, () -> seedAction(true));
        ssh.argvCalls.clear();

        asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of("svc", "nginx")));

        assertThat(ssh.argvCalls).noneMatch(argv -> !argv.isEmpty() && "sha256sum".equals(argv.get(0)));
    }

    // --- MONITOR classification grants nothing (the gate is unchanged) ------

    @Test
    void run_MonitorActionNotApproved_IsRefused() {
        AppUser user = saveUser();
        // A MONITOR recipe/action, NOT approved. MONITOR is display-only: it must not
        // auto-approve or carve out a read-only path — an unapproved action is refused.
        Seed seed = asUser(user, () -> seedFanOutAction(false));
        String items = "[{\"appName\":\"orders\",\"port\":8080}]";

        assertThatThrownBy(() -> asUser(user,
                () -> runService.run(seed.machineId(), seed.actionId(), Map.of("apps", items))))
                .isInstanceOf(ActionNotApprovedException.class);
        assertThat(ssh.argvCalls).isEmpty();
    }

    // --- host-vitals monitor (spec-023) -------------------------------------

    // NOT_SUPPORTED so the service's own transaction commits and the after-commit
    // dispatch fires inline. An approved host-vitals action (no params) runs through
    // the normal gate; the dashboard (spec-024) parses the raw stdout returned here.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void run_ApprovedHostVitals_RunsThroughGate_ReturnsRawStdout() {
        AppUser user = saveUser();
        Seed seed = asUser(user, () -> seedVitalsAction(true));

        Run queued = asUser(user, () -> runService.run(seed.machineId(), seed.actionId(), Map.of()));

        Run finished = awaitTerminal(queued.getId());
        assertThat(finished.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(finished.getExitCode()).isZero();
        // The fixed cpu template resolved verbatim (no params → the plain scalar N=1 run).
        assertThat(finished.getResolvedArgvJson()).contains("top", "-bn1");
        assertThat(finished.getStdout()).contains("ok");
        // A scalar host-vitals action is not a fan-out: no parent, no app label.
        assertThat(finished.getParentRunId()).isNull();
        assertThat(finished.getAppLabel()).isNull();
        assertThat(ssh.argvCalls).hasSize(1);
        assertThat(ssh.argvCalls.get(0)).containsExactly("top", "-bn1");
    }

    @Test
    void run_HostVitalsNotApproved_IsRefused() {
        AppUser user = saveUser();
        // MONITOR is display-only: an unapproved host-vitals action is refused like any.
        Seed seed = asUser(user, () -> seedVitalsAction(false));

        assertThatThrownBy(() -> asUser(user,
                () -> runService.run(seed.machineId(), seed.actionId(), Map.of())))
                .isInstanceOf(ActionNotApprovedException.class);
        assertThat(ssh.argvCalls).isEmpty();
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

    /**
     * A CUSTOM recipe/action wrapping an on-box script (spec-015): the leading LITERAL
     * token is the absolute script path, no params. Approved when {@code approve}, so it
     * is content-pinned via the recording SSH fake's {@code sha256sum} response.
     */
    private Seed seedCustomAction(String scriptPath) {
        Machine machine = machineService.register(new RegisterMachineInput("host", 22, "root"));
        Recipe recipe = recipeService.create(new CreateRecipeInput(
                machine.getId(), "custom", "custom commands", RecipeType.CUSTOM));
        Action action = actionService.addAction(new AddActionInput(
                recipe.getId(), "run script", "runs the wrapped script", false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, scriptPath)),
                List.of()));
        approvalService.submitForApproval(action.getId());
        approvalService.approve(action.getId());
        return new Seed(machine.getId(), action.getId());
    }

    /**
     * A MONITOR recipe with a fan-out probe action: a fixed single-app template
     * referencing the {@code app-name}/{@code port} components plus one
     * {@code APP_PORT_LIST} composite param (spec-022). Approved when {@code approve}.
     */
    private Seed seedFanOutAction(boolean approve) {
        Machine machine = machineService.register(new RegisterMachineInput("host", 22, "root"));
        Recipe recipe = recipeService.create(new CreateRecipeInput(
                machine.getId(), "monitor", "app monitors", RecipeType.MONITOR));
        Action action = actionService.addAction(new AddActionInput(
                recipe.getId(), "probe apps", "probe each app", false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "probe"),
                        new ArgTokenInput(TokenKind.PARAM, "app-name"),
                        new ArgTokenInput(TokenKind.PARAM, "port")),
                List.of(new ParamDefInput("apps", ParamKind.APP_PORT_LIST, null, null, null, null))));
        if (approve) {
            approvalService.submitForApproval(action.getId());
            approvalService.approve(action.getId());
        }
        return new Seed(machine.getId(), action.getId());
    }

    /**
     * The universal host-vitals monitor (spec-023): a MONITOR recipe {@code monitor
     * machine} with the fixed, param-free {@code cpu} action ({@code top -bn1}).
     * Approved when {@code approve}.
     */
    private Seed seedVitalsAction(boolean approve) {
        Machine machine = machineService.register(new RegisterMachineInput("host", 22, "root"));
        Recipe recipe = recipeService.create(new CreateRecipeInput(
                machine.getId(), "monitor machine", "host vitals", RecipeType.MONITOR));
        Action action = actionService.addAction(new AddActionInput(
                recipe.getId(), "cpu", "cpu snapshot", false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "top"),
                        new ArgTokenInput(TokenKind.LITERAL, "-bn1")),
                List.of()));
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
        user.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
        user.setName("user");
        return users.save(user);
    }

    private <R> R asUser(AppUser user, Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), body::get);
    }

    /** Records the events, streams, and completion signalled to a subscriber. */
    private static final class Recorder implements RunOutputHub.Subscriber {

        private final List<RunOutputHub.OutputEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void onEvent(RunOutputHub.OutputEvent event) {
            events.add(event);
        }

        @Override
        public void onComplete() {
            completed.set(true);
        }

        boolean completed() {
            return completed.get();
        }

        /** The concatenated data of every event on {@code streamName}. */
        String stream(String streamName) {
            StringBuilder builder = new StringBuilder();
            for (RunOutputHub.OutputEvent event : events) {
                if (event.stream().equals(streamName)) {
                    builder.append(event.data());
                }
            }
            return builder.toString();
        }
    }
}
