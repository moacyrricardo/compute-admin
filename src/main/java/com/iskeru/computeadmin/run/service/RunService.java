package com.iskeru.computeadmin.run.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.event.MachineReachedEvent;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.service.ActionNotApprovedException;
import com.iskeru.computeadmin.recipe.service.ActionNotFoundException;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionSnapshot;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.recipe.service.ParamValidationException;
import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.model.RunStatus;
import com.iskeru.computeadmin.run.repository.RunRepository;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import jakarta.ws.rs.BadRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p><strong>Fan-out (spec-022).</strong> When the action declares an
 * {@code APP_PORT_LIST} param (a monitor probe over a repeatable {@code (app-name,
 * port)} list), {@link #run} enters fan-out mode: it runs the <em>fixed single-app
 * template</em> <strong>once per item</strong>, binding each item's {@code app-name}/
 * {@code port} through the <em>unchanged</em> {@link ParamBinder#bind} — discrete,
 * validated argv, POSIX-quoted by the adapter exactly as today. It <strong>never
 * builds a looping or variable shell command</strong>, so the S4 guarantee holds per
 * item. All items are bound <em>before</em> anything executes (one invalid item fails
 * the whole run with nothing dispatched). The poll persists one parent {@link Run}
 * plus one child per item; each child streams under its own id and is labelled by its
 * item's {@code appName}; the parent aggregates child status ({@code DONE} iff all
 * children {@code DONE}, else {@code FAILED}). The gate is checked once, for the
 * action — the item list is a runtime value, never part of the content hash. A scalar
 * action (no {@code APP_PORT_LIST}) is exactly the pre-022 path, a fan-out of size 1.
 *
 * <p>spec-005; fan-out added in spec-022.
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
        // even though a structural edit already resets approval (spec-004). The gate is
        // checked once, for the action — the fixed template is what was approved; the
        // fan-out item list is a runtime value, not part of the content hash (spec-022).
        if (!ActionSnapshot.hash(action).equals(action.getApprovedSnapshotHash())) {
            throw new ActionModifiedException(actionId);
        }

        SshTarget target = new SshTarget(machine.getHost(), machine.getPort(), machine.getLoginUser());
        AuthContext caller = CurrentUser.require();

        String appPortListName = appPortListParamName(action);
        if (appPortListName != null) {
            return runFanOut(action, machine, target, caller, params, appPortListName);
        }
        return runScalar(action, machine, target, caller, params);
    }

    /**
     * The pre-022 scalar path: one bind, one {@link Run}, one dispatch (also the N=1
     * degenerate case of fan-out). Behaviourally unchanged from spec-005.
     */
    private Run runScalar(Action action, Machine machine, SshTarget target, AuthContext caller,
                          Map<String, String> params) {
        // Binds and validates; the returned argv is sudo-prefixed by the binder when
        // the action requires it, so the SSH adapter is called with sudo=false.
        List<String> argv = paramBinder.bind(action, params);

        Run run = newRun(action, machine, caller, action.getApprovedSnapshotHash());
        run.setParamsJson(toJson(params == null ? Map.of() : params));
        run.setResolvedArgvJson(toJson(argv));
        Run saved = runs.save(run);

        String runId = saved.getId();
        String reachedMachineId = machine.getId();
        submitAfterCommit(() -> execute(runId, reachedMachineId, target, argv));
        return saved;
    }

    /**
     * Fan-out over an {@code APP_PORT_LIST} param (spec-022). Runs the fixed
     * single-app template once per {@code (app-name, port)} item. Every item is bound
     * through the unchanged {@link ParamBinder#bind} <em>before</em> any persistence
     * or dispatch, so a single invalid item fails the whole run with nothing
     * dispatched. Returns the parent {@link Run} handle; each child executes and
     * streams under its own id.
     */
    private Run runFanOut(Action action, Machine machine, SshTarget target, AuthContext caller,
                          Map<String, String> params, String appPortListName) {
        Map<String, String> supplied = params == null ? Map.of() : params;
        List<AppPortItem> items = parseItems(supplied.get(appPortListName));
        if (items.isEmpty()) {
            throw new BadRequestException(
                    "APP_PORT_LIST param '" + appPortListName + "' requires at least one item");
        }

        // Scalar params other than the composite are passed through to every item's bind.
        Map<String, String> baseParams = new LinkedHashMap<>(supplied);
        baseParams.remove(appPortListName);

        // Bind EVERY item first: each bind is the SAME fixed template with one item's
        // validated scalar values — discrete argv, never a shell loop (S4, per item).
        List<BoundItem> bound = new ArrayList<>();
        for (AppPortItem item : items) {
            Map<String, String> itemParams = new LinkedHashMap<>(baseParams);
            itemParams.put(ParamBinder.APP_NAME_COMPONENT, item.appName());
            itemParams.put(ParamBinder.PORT_COMPONENT, item.port());
            List<String> argv = paramBinder.bind(action, itemParams);
            bound.add(new BoundItem(item.appName(), itemParams, argv));
        }

        // Parent handle for the poll; children each a faithful spec-005 record.
        Run parent = newRun(action, machine, caller, action.getApprovedSnapshotHash());
        parent.setParamsJson(toJson(supplied));
        List<List<String>> childArgvs = new ArrayList<>();
        for (BoundItem b : bound) {
            childArgvs.add(b.argv());
        }
        parent.setResolvedArgvJson(toJson(childArgvs));
        Run savedParent = runs.save(parent);

        List<Dispatch> dispatches = new ArrayList<>();
        for (BoundItem b : bound) {
            Run child = newRun(action, machine, caller, action.getApprovedSnapshotHash());
            child.setParamsJson(toJson(b.params()));
            child.setResolvedArgvJson(toJson(b.argv()));
            child.setParentRunId(savedParent.getId());
            child.setAppLabel(b.appName());
            Run savedChild = runs.save(child);
            dispatches.add(new Dispatch(savedChild.getId(), b.argv()));
        }

        String reachedMachineId = machine.getId();
        for (Dispatch d : dispatches) {
            List<String> argv = d.argv();
            String childId = d.runId();
            submitAfterCommit(() -> execute(childId, reachedMachineId, target, argv));
        }
        return savedParent;
    }

    /** A fresh {@code QUEUED} run stamped with the caller and the approved hash. */
    private static Run newRun(Action action, Machine machine, AuthContext caller, String approvedHash) {
        Run run = new Run();
        run.setAction(action);
        run.setMachine(machine);
        run.setCallerUserId(caller.userId());
        run.setVia(caller.via());
        run.setApprovedSnapshotHash(approvedHash);
        run.setStatus(RunStatus.QUEUED);
        return run;
    }

    /** The name of the action's single {@code APP_PORT_LIST} param, or null if scalar. */
    private static String appPortListParamName(Action action) {
        for (ParamDef def : action.getParamDefs()) {
            if (def.getKind() == ParamKind.APP_PORT_LIST) {
                return def.getName();
            }
        }
        return null;
    }

    /**
     * Parses the runtime {@code APP_PORT_LIST} value — a JSON array of
     * {@code {"appName": ..., "port": ...}} objects — into raw string components. The
     * values are left <em>unvalidated</em> here; {@link ParamBinder#bind} validates
     * each item's {@code app-name}/{@code port} against the fixed item schema (S4).
     */
    private List<AppPortItem> parseItems(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = json.readTree(rawJson);
        } catch (JsonProcessingException e) {
            throw new ParamValidationException("APP_PORT_LIST value is not valid JSON");
        }
        if (!root.isArray()) {
            throw new ParamValidationException("APP_PORT_LIST value must be a JSON array");
        }
        List<AppPortItem> items = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode appNode = node.get("appName");
            JsonNode portNode = node.get("port");
            String appName = appNode == null || appNode.isNull() ? null : appNode.asText();
            String port = portNode == null || portNode.isNull() ? null : portNode.asText();
            items.add(new AppPortItem(appName, port));
        }
        return items;
    }

    /** One raw {@code (appName, port)} item as supplied at runtime (unvalidated). */
    private record AppPortItem(String appName, String port) {
    }

    /** One item bound to the fixed template: its label, its scalar params, its argv. */
    private record BoundItem(String appName, Map<String, String> params, List<String> argv) {
    }

    /** A child run pending after-commit dispatch. */
    private record Dispatch(String runId, List<String> argv) {
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
     * The fan-out children of a parent run (spec-029): one per {@code (app-name, port)}
     * item, each carrying its {@code appLabel} so the fleet poll can correlate a fan-out
     * probe's output back to the app card that issued it. Ownership is enforced via
     * {@link #requireRun} on the parent (a not-owned/absent parent is 404); a scalar
     * (non-fan-out) run simply has no children.
     */
    public List<Run> childRuns(String parentId) {
        requireRun(parentId);
        return runs.findByParentRunId(parentId);
    }

    /**
     * Cancels one of the current user's live runs — the capability follow-mode
     * ({@code -f}) log streaming needs, since such a command never exits on its own
     * (spec-026). Owner-scoped through {@link #requireRun} (a not-owned/absent id 404s),
     * and a <strong>no-op on an already-terminal run</strong> (returned as-is).
     *
     * <p>The run is marked {@link RunStatus#STOPPED} and committed <em>first</em>; only
     * <strong>after commit</strong> is the transport signalled to close the channel and
     * the hub channel completed. That ordering makes cancellation win the race: by the
     * time {@code execStreaming} returns and {@code finish(...)} runs, the persisted
     * {@code STOPPED} is visible and {@code finish} no-ops on the terminal run. Completing
     * the hub detaches any live subscriber cleanly (reusing the spec-013 terminal-run
     * handling); it is idempotent with the worker's own completion.
     *
     * @throws RunNotFoundException 404 if absent or owned by another user.
     */
    @Transactional
    public Run cancel(String id) {
        Run run = requireRun(id);
        if (isTerminal(run.getStatus())) {
            return run;
        }
        run.setStatus(RunStatus.STOPPED);
        run.setExitCode(-1);
        run.setFinishedAt(Instant.now());
        Run saved = runs.save(run);
        String runId = saved.getId();
        submitAfterCommit(() -> {
            ssh.cancel(runId);
            hub.complete(runId, -1);
        });
        return saved;
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

    /** Whether a status is terminal (no further events will ever arrive). spec-016/026. */
    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.DONE
                || status == RunStatus.FAILED
                || status == RunStatus.INTERRUPTED
                || status == RunStatus.STOPPED;
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
            // The run id is the cancel key: cancel(runId) closes this channel so a
            // follow-mode (-f) stream can be stopped (spec-026).
            ssh.execStreaming(target, argv, false, sink, runId);
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
            // Only a still-QUEUED run advances to RUNNING: a run cancelled while queued is
            // already terminal (STOPPED) and must not be revived by the late worker (spec-026).
            if (run.getStatus() != RunStatus.QUEUED) {
                return;
            }
            run.setStatus(RunStatus.RUNNING);
            run.setStartedAt(Instant.now());
            runs.save(run);
        });
    }

    private void finish(String runId, String stdout, String stderr, int exitCode) {
        runs.findById(runId).ifPresent(run -> {
            // A run already marked terminal wins: a cancel (STOPPED) committed before the
            // channel closed must not be overwritten by the returning execStreaming (spec-026).
            if (isTerminal(run.getStatus())) {
                return;
            }
            run.setStdout(stdout);
            run.setStderr(stderr);
            run.setExitCode(exitCode);
            run.setStatus(exitCode == 0 ? RunStatus.DONE : RunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            runs.save(run);
            // A fan-out child completing may complete its parent (spec-022).
            if (run.getParentRunId() != null) {
                maybeCompleteParent(run.getParentRunId());
            }
        });
    }

    /**
     * Aggregates a fan-out parent's status once every child is terminal: {@code DONE}
     * iff all children are {@code DONE}, else {@code FAILED} (spec-022). Convergent —
     * recomputed from the children each time a child finishes, so a late/concurrent
     * child still lands the parent correctly. Never touches a still-running fan-out.
     */
    private void maybeCompleteParent(String parentId) {
        runs.findById(parentId).ifPresent(parent -> {
            if (isTerminal(parent.getStatus())) {
                return;
            }
            List<Run> children = runs.findByParentRunId(parentId);
            boolean allDone = true;
            for (Run child : children) {
                if (!isTerminal(child.getStatus())) {
                    return; // a child is still in flight — leave the parent pending.
                }
                if (child.getStatus() != RunStatus.DONE) {
                    allDone = false;
                }
            }
            parent.setStatus(allDone ? RunStatus.DONE : RunStatus.FAILED);
            parent.setFinishedAt(Instant.now());
            runs.save(parent);
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
