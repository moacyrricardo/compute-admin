package com.iskeru.computeadmin.discovery.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.discovery.AppPortItem;
import com.iskeru.computeadmin.discovery.DockerConsumer;
import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.machine.event.MachineReachedEvent;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.EditActionInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.ssh.SshExecutionException;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates recipe discovery: resolve one of the current user's machines, run
 * every registered {@link RecipeDiscoverer} over the {@link SshExecutor} port, and
 * persist each proposal as a {@code Recipe} + {@code Action}s <strong>in
 * {@code PENDING_APPROVAL}</strong> via the 004 services.
 *
 * <p>It <strong>never approves</strong> and <strong>never issues a mutating
 * command</strong>: the only commands sent are the discoverers' fixed read-only
 * probes; the mutating action templates (restart, enable-site, backup, …) are
 * persisted as pending proposals, never executed here. Persistence goes through
 * {@link RecipeService}/{@link ActionService} (and {@link ApprovalService} only for
 * the benign {@code DRAFT → PENDING_APPROVAL} submit) — never a repository — so
 * ownership scoping and the gate stay centralised. A not-owned machine reads as 404
 * ({@link MachineService#requireMachine(String)}).
 *
 * <p><strong>Idempotent re-discovery (spec-021).</strong> A discovered recipe's
 * identity is the triple {@code (machine, type, name)}; discovery
 * <em>reconciles</em> rather than duplicates. Each proposed recipe is
 * get-or-created by that triple, and each proposed action is matched by name within
 * it: a missing action is added and submitted; a not-yet-approved (DRAFT/PENDING)
 * one is refreshed in place; an already-{@code APPROVED} one is left untouched — if
 * the proposal now differs from what was approved, that is <em>surfaced</em>
 * (never auto-adopted, never duplicated). Approval stays UI-only.
 *
 * <p><strong>Family enablement (spec-035).</strong> Before probing, the discoverer list
 * is filtered by the machine's enabled {@link DiscovererFamily} set
 * ({@link DiscoveryEnablementService}); a disabled family is skipped entirely — no probe,
 * no proposal. Docker is default-off (root-equivalent socket). This is upstream of the
 * approval gate: an enabled family still only proposes {@code PENDING_APPROVAL} actions.
 *
 * <p>spec-006; idempotency in spec-021; family enablement in spec-035.
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

    /**
     * What reconciliation did with one proposed action on a re-discovery (spec-021):
     * <ul>
     *   <li>{@code CREATED} — no such action existed; added and submitted.
     *   <li>{@code REFRESHED} — a not-yet-approved action's proposal was re-applied.
     *   <li>{@code UNCHANGED} — an approved action whose proposal still matches.
     *   <li>{@code DIFFERS_AWAITING_REAPPROVAL} — an approved action whose proposal
     *       now differs; the approval is left intact and the new definition surfaced.
     *   <li>{@code SKIPPED_REVOKED} — a revoked action, left as-is (not resurrected).
     * </ul>
     */
    public enum ReconcileOutcome {
        CREATED, REFRESHED, UNCHANGED, DIFFERS_AWAITING_REAPPROVAL, SKIPPED_REVOKED
    }

    /**
     * One reconciled action and how discovery treated it. {@code proposed} carries the
     * newly-proposed definition only for {@code DIFFERS_AWAITING_REAPPROVAL} (so the UI
     * can show "discovery would change this approved action — review and re-approve to
     * adopt"); it is {@code null} otherwise.
     */
    public record ReconciledAction(Action action, ReconcileOutcome outcome, ProposedAction proposed) {
    }

    /** A reconciled proposal: the recipe and its reconciled actions. */
    public record DiscoveredRecipe(Recipe recipe, List<ReconciledAction> actions) {
    }

    /**
     * The result of a discovery pass (spec-070): the reconciled recipes, whether the run
     * was <strong>partial</strong> (a transport failure skipped one or more families or
     * the session could not be opened at all), {@code connectionLost} is set when the
     * shared SSH session could not be opened <em>or died mid-pass</em> (so the families
     * after the failure went unprobed — distinct from an honest per-family failure), and
     * {@code failedFamilies} names every {@link DiscovererFamily} that did not get a clean
     * probe (the one that failed plus, on a mid-pass session loss, every enabled family
     * after it). A clean run is {@code partial == false}, {@code connectionLost == false},
     * with an empty {@code failedFamilies}.
     */
    public record DiscoveryOutcome(List<DiscoveredRecipe> recipes, boolean partial,
                                   boolean connectionLost, List<DiscovererFamily> failedFamilies) {
    }

    private final MachineService machineService;
    private final RecipeService recipeService;
    private final ActionService actionService;
    private final ApprovalService approvalService;
    private final DiscoveryEnablementService enablementService;
    private final SshExecutor ssh;
    private final List<RecipeDiscoverer> discoverers;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    public DiscoveryService(MachineService machineService, RecipeService recipeService,
                            ActionService actionService, ApprovalService approvalService,
                            DiscoveryEnablementService enablementService,
                            SshExecutor ssh, List<RecipeDiscoverer> discoverers,
                            PlatformTransactionManager transactionManager,
                            ApplicationEventPublisher events, ObjectMapper json) {
        this.machineService = machineService;
        this.recipeService = recipeService;
        this.actionService = actionService;
        this.approvalService = approvalService;
        this.enablementService = enablementService;
        this.ssh = ssh;
        this.discoverers = discoverers;
        this.tx = new TransactionTemplate(transactionManager);
        this.events = events;
        this.json = json;
    }

    /**
     * Discovers and persists proposals for one of the current user's machines.
     *
     * <p><strong>Resource scoping (spec-013).</strong> The SSH probes are the slow,
     * network-bound phase, so they run with <em>no open transaction</em>: resolve the
     * machine (a read), run every {@link RecipeDiscoverer} into an in-memory list of
     * {@link ProposedRecipe}s, and only then persist them all in <em>one short
     * transaction</em> driven by an injected {@link TransactionTemplate}. Using the
     * template (rather than a bare {@code @Transactional} on this method or a private
     * {@code persist(...)}) sidesteps the self-invocation trap — a self-called
     * annotated method is a no-op through the Spring proxy — so the nested
     * {@code RecipeService}/{@code ActionService}/{@code ApprovalService} writes join
     * one transaction. Touching only scalar getters and the EAGER {@code tags} during
     * the no-transaction probe phase keeps the detached {@code Machine} safe (no
     * {@code LazyInitializationException}).
     *
     * @throws com.iskeru.computeadmin.machine.service.MachineNotFoundException 404 if
     *         the machine is absent or owned by another user.
     */
    public DiscoveryOutcome discover(String machineId) {
        Machine machine = machineService.requireMachine(machineId);
        // Per-machine enablement (spec-035): skip a disabled family entirely — no probe,
        // no proposal. Docker is default-off (root-equivalent socket); the read-only
        // families the login user can already run stay on. This gate is upstream of, and
        // distinct from, the approval gate — enabled discoverers still only propose.
        Set<DiscovererFamily> enabled = enablementService.enabledFamilies(machineId);
        SshTarget target = Probes.target(machine);

        // Probe phase — no open transaction; collect proposals in memory. All the
        // discoverers share ONE authenticated session (spec-070 L1): one discovery pass =
        // one SSH handshake, not one per probe.
        List<ProposedRecipe> proposals = new ArrayList<>();
        List<DiscovererFamily> failed = new ArrayList<>();
        // A boxed flag the session lambda can set: at least one discoverer completed a
        // probe pass, so the box was actually reached (not a connect-then-drop).
        boolean[] reached = {false};
        // Boxed: the shared session died mid-pass (a transport failure on some family), so
        // every enabled family after it went unprobed — reported distinctly from an honest
        // per-family failure (070 follow-up, BOL-900).
        boolean[] sessionLost = {false};
        boolean connectionFailedToOpen = false;
        try {
            ssh.withSession(target, session -> {
                for (RecipeDiscoverer discoverer : discoverers) {
                    if (!enabled.contains(discoverer.family())) {
                        continue;                                   // enablement gate is upstream of the fold below
                    }
                    if (sessionLost[0]) {
                        // The shared session already died on an earlier family; this enabled
                        // family never got probed. Record it as failed rather than let a
                        // caller read its absence as "probed and empty".
                        failed.add(discoverer.family());
                        continue;
                    }
                    try {
                        proposals.addAll(discoverer.discover(machine, session));   // L1: one open session
                        reached[0] = true;
                    } catch (SshExecutionException e) {                            // L0: degrade — transport only
                        // A transport failure mid-pass means the one shared session died.
                        // Flag connection-lost and record this family; the loop then folds
                        // every remaining enabled family into failedFamilies too, so nothing
                        // skipped is silently reported as "probed and empty".
                        log.warn("discovery: session to {} lost mid-pass at family {} — remaining families unprobed",
                                machineId, discoverer.family(), e);
                        sessionLost[0] = true;
                        failed.add(discoverer.family());
                    }
                    // NB: a non-SshExecutionException (e.g. a discoverer NPE) is NOT caught
                    // here — it aborts loudly, as it should. Only transport failures degrade.
                }
                return null;
            });
        } catch (SshExecutionException e) {
            // connect/auth failed BEFORE any probe ran → real outage; degrade, don't abort.
            log.warn("discovery: could not open a session to {}", machineId, e);
            connectionFailedToOpen = true;
        }

        boolean connectionLost = connectionFailedToOpen || sessionLost[0];
        boolean partial = connectionLost || !failed.isEmpty();
        // Only announce ONLINE when a probe actually succeeded — never off a run that
        // connected then dropped. A listener refreshes the machine to ONLINE
        // asynchronously (via = SYSTEM). spec-019/070.
        if (reached[0]) {
            events.publishEvent(new MachineReachedEvent(machineId, Instant.now()));
        }
        // Persist phase — one short transaction.
        List<DiscoveredRecipe> persisted = tx.execute(status -> persist(machineId, proposals));
        return new DiscoveryOutcome(persisted, partial, connectionLost, List.copyOf(failed));
    }

    private List<DiscoveredRecipe> persist(String machineId, List<ProposedRecipe> rawProposals) {
        // spec-075 B2: before persisting, fold a fingerprinted well-known service's ports onto its
        // typed family recipe (NGINX/DATABASE) when one is present in the same pass, so a service is
        // represented once instead of twice (its family recipe + the generic app monitor).
        CrossFamilyReconciled reconciled = reconcileCrossFamily(rawProposals);
        List<ProposedRecipe> proposals = reconciled.proposals();
        Set<String> forceRefreshEmpty = reconciled.forceRefreshEmpty();
        List<DiscoveredRecipe> discovered = new ArrayList<>();
        for (ProposedRecipe proposal : proposals) {
            // Reconcile by identity triple (machine, type, name): reuse the recipe this
            // discoverer owns on this machine, never mint a duplicate (spec-021).
            Recipe recipe = recipeService.getOrCreateDiscovered(
                    machineId, proposal.type(), proposal.name(), proposal.description());
            List<ReconciledAction> actions = new ArrayList<>();
            for (ProposedAction proposedAction : proposal.actions()) {
                actions.add(reconcileAction(recipe, proposedAction));
            }
            // Refresh the discovery-pre-filled (app-name, port) list in place (spec-025).
            // It is a runtime value, not part of any action's content hash (spec-022), so
            // reconciling the apps never re-opens an approval — re-discovery just picks up
            // a new/removed app on the same recipe (no duplicate card). A docker compose
            // monitor (spec-033/061) refreshes BOTH its classified consumers AND its
            // inspect-enriched published-port items into the same un-audited column as one
            // combined object (no new schema); a native recipe writes the bare item array.
            if (!proposal.dockerConsumers().isEmpty()) {
                recipe = recipeService.refreshDiscoveredAppPortList(
                        recipe.getId(),
                        toDockerJson(proposal.dockerConsumers(), proposal.appPortList()));
            } else if (!proposal.appPortList().isEmpty()
                    || forceRefreshEmpty.contains(recipeIdentity(proposal))) {
                // A generic app-monitor whose only apps were relocated by B2 refreshes to an empty
                // list, so the moved-away entries actually disappear on re-discovery (spec-021).
                recipe = recipeService.refreshDiscoveredAppPortList(
                        recipe.getId(), toJson(proposal.appPortList()));
            }
            discovered.add(new DiscoveredRecipe(recipe, actions));
        }
        return discovered;
    }

    /** A proposal's within-machine identity (its reconcile triple minus the machine): type + name. */
    private static String recipeIdentity(ProposedRecipe proposal) {
        return proposal.type().name() + " " + proposal.name();
    }

    /**
     * The output of {@link #reconcileCrossFamily}: the (possibly rewritten) proposal list, plus the
     * {@link #recipeIdentity(ProposedRecipe)} keys of any generic app-monitor recipe whose app-port
     * list must be refreshed <em>even when it is now empty</em> — because B2 relocated every one of
     * its items away, so the persisted list has to be cleared rather than left stale.
     */
    record CrossFamilyReconciled(List<ProposedRecipe> proposals, Set<String> forceRefreshEmpty) {
    }

    /**
     * Post-pass cross-family reconciliation (spec-075 B2). When a typed family recipe (NGINX or
     * DATABASE) is proposed in the same pass, a fingerprinted well-known service's {@code (addr,port)}
     * items are moved off the generic app-monitor recipe and onto that family recipe's
     * {@code app_port_list}; a well-known service is then represented <strong>once</strong> — under
     * its own family recipe, now carrying its listening ports — instead of twice. Operates on the
     * accumulated proposal set (order-independent), never on discoverer ordering; a pure transform
     * (no persistence), so it is unit-testable in isolation.
     *
     * <p>The relocation target is chosen by {@link ServiceCatalog#foldFamilyFor(String)}: nginx items
     * fold under NGINX, database-engine items under DATABASE. Items go to the first typed recipe of
     * that family in the pass. When no typed family recipe is present, nothing moves — the fingerprinted
     * items stay on the generic monitor (still grouped by their shared contextKey, spec-066).
     */
    static CrossFamilyReconciled reconcileCrossFamily(List<ProposedRecipe> proposals) {
        Set<RecipeType> presentTyped = EnumSet.noneOf(RecipeType.class);
        for (ProposedRecipe p : proposals) {
            if (p.type() == RecipeType.NGINX || p.type() == RecipeType.DATABASE) {
                presentTyped.add(p.type());
            }
        }
        if (presentTyped.isEmpty()) {
            return new CrossFamilyReconciled(proposals, Set.of());
        }

        Map<RecipeType, List<AppPortItem>> relocated = new EnumMap<>(RecipeType.class);
        Set<String> forceRefreshEmpty = new HashSet<>();
        List<ProposedRecipe> stage = new ArrayList<>();
        for (ProposedRecipe p : proposals) {
            // Only a native generic app-monitor is a relocation source: MONITOR type, carrying a
            // bare app-port list (never a docker combined object, whose ports are DNAT truth).
            if (p.type() != RecipeType.MONITOR || p.appPortList().isEmpty()
                    || !p.dockerConsumers().isEmpty()) {
                stage.add(p);
                continue;
            }
            List<AppPortItem> keep = new ArrayList<>();
            for (AppPortItem item : p.appPortList()) {
                RecipeType family = ServiceCatalog.foldFamilyFor(item.appName());
                if (family != null && presentTyped.contains(family)) {
                    relocated.computeIfAbsent(family, k -> new ArrayList<>()).add(item);
                } else {
                    keep.add(item);
                }
            }
            if (keep.size() == p.appPortList().size()) {
                stage.add(p);
            } else {
                if (keep.isEmpty()) {
                    forceRefreshEmpty.add(recipeIdentity(p));
                }
                stage.add(new ProposedRecipe(p.type(), p.name(), p.description(),
                        p.actions(), keep, p.dockerConsumers()));
            }
        }
        if (relocated.isEmpty()) {
            return new CrossFamilyReconciled(proposals, Set.of());
        }

        List<ProposedRecipe> out = new ArrayList<>();
        for (ProposedRecipe p : stage) {
            List<AppPortItem> add = (p.type() == RecipeType.NGINX || p.type() == RecipeType.DATABASE)
                    ? relocated.remove(p.type()) : null;
            if (add == null) {
                out.add(p);
                continue;
            }
            List<AppPortItem> merged = new ArrayList<>(p.appPortList());
            merged.addAll(add);
            out.add(new ProposedRecipe(p.type(), p.name(), p.description(),
                    p.actions(), merged, p.dockerConsumers()));
        }
        return new CrossFamilyReconciled(out, forceRefreshEmpty);
    }

    /**
     * Reconciles one proposed action against the recipe's existing action of the same
     * name, applying the spec-021 state-machine rules. Every write goes through the 004
     * services ({@link ActionService}/{@link ApprovalService}); discovery never
     * approves and never edits an approved action.
     */
    private ReconciledAction reconcileAction(Recipe recipe, ProposedAction proposed) {
        Action existing = actionService.findOnRecipe(recipe.getId(), proposed.name()).orElse(null);
        if (existing == null) {
            // No such action → add it (DRAFT) and submit → PENDING_APPROVAL (today's path).
            Action added = actionService.addAction(new AddActionInput(
                    recipe.getId(), proposed.name(), proposed.description(),
                    proposed.sudo(), proposed.argTokens(), proposed.paramDefs()));
            Action pending = approvalService.submitForApproval(added.getId());
            return new ReconciledAction(pending, ReconcileOutcome.CREATED, null);
        }
        return switch (existing.getApprovalState()) {
            // Not yet approved → refresh the proposal in place (picks up a changed
            // ALLOWED_SET etc.), keeping it PENDING_APPROVAL. editAction resets it to
            // DRAFT, so re-submit; safe because it is only ever called here on a
            // not-yet-approved action.
            case DRAFT, PENDING_APPROVAL -> {
                Action edited = actionService.editAction(existing.getId(), new EditActionInput(
                        proposed.name(), proposed.description(), proposed.sudo(),
                        proposed.argTokens(), proposed.paramDefs()));
                Action pending = approvalService.submitForApproval(edited.getId());
                yield new ReconciledAction(pending, ReconcileOutcome.REFRESHED, null);
            }
            // Approved → never touch the approval. Compare the proposed definition's
            // content hash against the one bound at approval: equal ⇒ no-op; different
            // ⇒ surface the diff so a human can review and re-approve to adopt.
            case APPROVED -> {
                String proposedHash = actionService.snapshotHashOf(
                        proposed.sudo(), proposed.argTokens(), proposed.paramDefs());
                if (proposedHash.equals(existing.getApprovedSnapshotHash())) {
                    yield new ReconciledAction(existing, ReconcileOutcome.UNCHANGED, null);
                }
                yield new ReconciledAction(existing, ReconcileOutcome.DIFFERS_AWAITING_REAPPROVAL, proposed);
            }
            // Revoked → leave it; do not resurrect.
            case REVOKED -> new ReconciledAction(existing, ReconcileOutcome.SKIPPED_REVOKED, null);
        };
    }

    /**
     * Serialises the pre-filled items to the {@code [{"appName","port","runtime"}]}
     * JSON array shape {@code RunService} binds per fan-out item (spec-022/025).
     */
    private String toJson(List<AppPortItem> items) {
        try {
            return json.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            // Fixed record shape; a serialisation failure here is a programming error.
            throw new IllegalStateException("Could not serialise app-port list", e);
        }
    }

    /**
     * Serialises a docker proposal into the recipe's {@code appPortList} column as one combined
     * object {@code {"dockerConsumers":[…],"appPortList":[…]}} (spec-061, retiring spec-033's
     * consumers-only object). The object shape tells the monitor read apart from the native
     * {@code [{"appName","port"}]} array; both members ride the same CLOB with no new schema.
     * Readers are tolerant: a pre-061 row carrying only {@code dockerConsumers} still parses (its
     * missing {@code appPortList} reads as the empty list).
     */
    private String toDockerJson(List<DockerConsumer> consumers, List<AppPortItem> appPortList) {
        try {
            return json.writeValueAsString(
                    Map.of("dockerConsumers", consumers, "appPortList", appPortList));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise docker proposal", e);
        }
    }
}
