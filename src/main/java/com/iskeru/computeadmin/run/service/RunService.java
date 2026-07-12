package com.iskeru.computeadmin.run.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.event.MachineReachedEvent;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.service.ActionNotApprovedException;
import com.iskeru.computeadmin.recipe.service.ActionNotFoundException;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionSnapshot;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.model.RunStatus;
import com.iskeru.computeadmin.run.repository.RunRepository;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The <strong>single gate entry point</strong> both the UI and the MCP
 * {@code run_action} tool (spec 008) reach to execute an approved action. The gate
 * is enforced here, in exactly one place (ARCH.md):
 *
 * <ol>
 *   <li>{@link MachineService#requireMachine} and {@link ActionService#requireAction}
 *       scope to {@link CurrentUser#require()} — a user runs only against his own
 *       machines/actions; a mismatch or absence reads as 404.</li>
 *   <li>the action must be {@link ApprovalState#APPROVED}, else
 *       {@link ActionNotApprovedException} (409);</li>
 *   <li>the action's <em>live</em> content hash must equal its
 *       {@code approvedSnapshotHash}, else {@link ActionModifiedException} (409) —
 *       re-checking the spec-004 binding at run time;</li>
 *   <li>{@link ParamBinder#bind} validates params and yields the discrete argv,
 *       else {@link com.iskeru.computeadmin.recipe.service.ParamValidationException}
 *       (400).</li>
 * </ol>
 *
 * <p>Only then is an append-only {@link Run} persisted ({@code QUEUED}) recording
 * exactly what will run, and the command dispatched <strong>asynchronously</strong>
 * onto the bounded {@code runExecutor} pool. The async task flips the run to
 * {@code RUNNING}, streams output through {@link RunOutputHub} while appending it to
 * the run, and lands on {@code DONE}/{@code FAILED} with the captured exit code — or,
 * if the owning process died mid-run, on {@code INTERRUPTED} once the boot
 * {@code RunReconciler} resolves the orphaned row (spec-016). Submission is deferred
 * to <em>after commit</em> so the worker can never observe a run that has not yet
 * been persisted.
 *
 * <p>spec-005.
 */
@Service
public class RunService {

    private final RunRepository runs;
    private final MachineService machineService;
    private final ActionService actionService;
    private final ParamBinder paramBinder;
    private final SshExecutor ssh;
    private final RunOutputHub hub;
    private final TaskExecutor runExecutor;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public RunService(RunRepository runs, MachineService machineService, ActionService actionService,
                      ParamBinder paramBinder, SshExecutor ssh, RunOutputHub hub,
                      @Qualifier("runExecutor") TaskExecutor runExecutor, ObjectMapper json,
                      ApplicationEventPublisher events) {
        this.runs = runs;
        this.machineService = machineService;
        this.actionService = actionService;
        this.paramBinder = paramBinder;
        this.ssh = ssh;
        this.hub = hub;
        this.runExecutor = runExecutor;
        this.json = json;
        this.events = events;
    }

    /**
     * Runs an approved action against one of the current user's machines. Persists a
     * {@code QUEUED} run and returns it immediately; the command executes and streams
     * asynchronously.
     *
     * @throws com.iskeru.computeadmin.machine.service.MachineNotFoundException 404
     * @throws ActionNotFoundException                                         404
     * @throws ActionNotApprovedException                                      409
     * @throws ActionModifiedException                                         409
     * @throws com.iskeru.computeadmin.recipe.service.ParamValidationException 400
     */
    @Transactional
    public Run run(String machineId, String actionId, Map<String, String> params) {
        Machine machine = machineService.requireMachine(machineId);
        Action action = actionService.requireAction(actionId);
        // The action must live on the requested machine. A cross-machine id reads as
        // 404 (existence never leaked), matching the ownership convention.
        if (!action.getRecipe().getMachine().getId().equals(machine.getId())) {
            throw new ActionNotFoundException(actionId);
        }
        if (action.getApprovalState() != ApprovalState.APPROVED) {
            throw new ActionNotApprovedException(actionId);
        }
        // Re-verify the approved binding at run time: a drifted definition never runs,
        // even though a structural edit already resets approval (spec-004).
        if (!ActionSnapshot.hash(action).equals(action.getApprovedSnapshotHash())) {
            throw new ActionModifiedException(actionId);
        }

        // Binds and validates; the returned argv is sudo-prefixed by the binder when
        // the action requires it, so the SSH adapter is called with sudo=false.
        List<String> argv = paramBinder.bind(action, params);
        SshTarget target = new SshTarget(machine.getHost(), machine.getPort(), machine.getLoginUser());

        AuthContext caller = CurrentUser.require();
        Run run = new Run();
        run.setAction(action);
        run.setMachine(machine);
        run.setCallerUserId(caller.userId());
        run.setVia(caller.via());
        run.setParamsJson(toJson(params == null ? Map.of() : params));
        run.setResolvedArgvJson(toJson(argv));
        run.setApprovedSnapshotHash(action.getApprovedSnapshotHash());
        run.setStatus(RunStatus.QUEUED);
        Run saved = runs.save(run);

        String runId = saved.getId();
        String reachedMachineId = machine.getId();
        submitAfterCommit(() -> execute(runId, reachedMachineId, target, argv));
        return saved;
    }

    /**
     * The current user's run by id.
     *
     * @throws RunNotFoundException 404 if absent or owned by another user.
     */
    public Run requireRun(String id) {
        return runs.findByIdAndAction_Recipe_Machine_Owner_Id(id, CurrentUser.require().userId())
                .orElseThrow(() -> new RunNotFoundException(id));
    }

    /**
     * Subscribes to a run's output after confirming the current user owns it (a
     * not-owned or absent id is a 404 before any streaming starts).
     *
     * <p>A live run ({@code QUEUED}/{@code RUNNING}) attaches to the hub for the
     * buffered prefix then the live tail. A terminal run ({@code DONE}/{@code FAILED}/
     * {@code INTERRUPTED}) attaches only <em>if its channel is still retained</em>
     * ({@link RunOutputHub#attachIfPresent}); once the channel has been evicted the
     * subscriber is served from the persisted, append-only {@code Run} instead — which
     * is exactly what a live replay would have produced. This avoids resurrecting an
     * evicted channel (which would create an empty one and hang forever). spec-013.
     *
     * <p>An {@code INTERRUPTED} run (resolved by the boot {@code RunReconciler}) has
     * no live channel — the process that owned it died — so treating it as terminal
     * here is what makes a post-reconcile subscribe replay from the persisted row
     * instead of hanging on a freshly created live channel. spec-016.
     */
    public void subscribeToOutput(String id, RunOutputHub.Subscriber subscriber) {
        Run run = requireRun(id);
        if (isTerminal(run.getStatus())) {
            if (!hub.attachIfPresent(id, subscriber)) {
                replayPersisted(run, subscriber);
            }
        } else {
            hub.subscribe(id, subscriber);
        }
    }

    /** Whether a status is terminal (no further events will ever arrive). spec-016. */
    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.DONE
                || status == RunStatus.FAILED
                || status == RunStatus.INTERRUPTED;
    }

    /**
     * Synthesizes a subscriber's stream from the persisted run: the recorded stdout
     * then stderr as single chunks, the terminal {@code exit} event, and
     * {@code onComplete}. The fallback for a terminal run whose hub channel has been
     * evicted.
     */
    private void replayPersisted(Run run, RunOutputHub.Subscriber subscriber) {
        String stdout = run.getStdout();
        if (stdout != null && !stdout.isEmpty()) {
            subscriber.onEvent(new RunOutputHub.OutputEvent(RunOutputHub.OutputEvent.STDOUT, stdout));
        }
        String stderr = run.getStderr();
        if (stderr != null && !stderr.isEmpty()) {
            subscriber.onEvent(new RunOutputHub.OutputEvent(RunOutputHub.OutputEvent.STDERR, stderr));
        }
        int exitCode = run.getExitCode() == null ? -1 : run.getExitCode();
        subscriber.onEvent(new RunOutputHub.OutputEvent(RunOutputHub.OutputEvent.EXIT, String.valueOf(exitCode)));
        subscriber.onComplete();
    }

    // --- async execution (runs on the runExecutor pool, no bound CurrentUser) ------

    private void execute(String runId, String machineId, SshTarget target, List<String> argv) {
        markRunning(runId);
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        OutputSink sink = new OutputSink() {
            @Override
            public void onStdout(String chunk) {
                out.append(chunk);
                hub.publish(runId, new RunOutputHub.OutputEvent(RunOutputHub.OutputEvent.STDOUT, chunk));
            }

            @Override
            public void onStderr(String chunk) {
                err.append(chunk);
                hub.publish(runId, new RunOutputHub.OutputEvent(RunOutputHub.OutputEvent.STDERR, chunk));
            }

            @Override
            public void onComplete(int exitCode) {
                finish(runId, out.toString(), err.toString(), exitCode);
                hub.complete(runId, exitCode);
            }
        };
        try {
            // sudo is already baked into argv by the binder, so pass sudo=false here.
            ssh.execStreaming(target, argv, false, sink);
            // The SSH channel executed (whatever the command's exit code): the box is
            // reachable, so announce it. A listener refreshes the machine to ONLINE
            // asynchronously (via = SYSTEM); a connection failure throws below and
            // publishes nothing. spec-019.
            events.publishEvent(new MachineReachedEvent(machineId, Instant.now()));
        } catch (RuntimeException e) {
            String captured = err.length() == 0 ? String.valueOf(e.getMessage()) : err.toString();
            finish(runId, out.toString(), captured, -1);
            hub.complete(runId, -1);
        }
    }

    private void markRunning(String runId) {
        runs.findById(runId).ifPresent(run -> {
            run.setStatus(RunStatus.RUNNING);
            run.setStartedAt(Instant.now());
            runs.save(run);
        });
    }

    private void finish(String runId, String stdout, String stderr, int exitCode) {
        runs.findById(runId).ifPresent(run -> {
            run.setStdout(stdout);
            run.setStderr(stderr);
            run.setExitCode(exitCode);
            run.setStatus(exitCode == 0 ? RunStatus.DONE : RunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            runs.save(run);
        });
    }

    /**
     * Dispatches {@code task} onto the run pool only once the surrounding
     * transaction has committed, so the worker never races the persisted run. With
     * no active transaction (e.g. a direct service call in a test), it dispatches
     * immediately.
     */
    private void submitAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runExecutor.execute(task);
                }
            });
        } else {
            runExecutor.execute(task);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // The inputs are a String map and a String list — serialization cannot fail.
            throw new IllegalStateException("Failed to serialize run JSON", e);
        }
    }
}
