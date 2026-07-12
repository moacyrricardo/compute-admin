package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.machine.event.MachineReachedEvent;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.EditActionInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.ssh.SshExecutor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
 * <p>spec-006; idempotency in spec-021.
 */
@Service
public class DiscoveryService {

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

    private final MachineService machineService;
    private final RecipeService recipeService;
    private final ActionService actionService;
    private final ApprovalService approvalService;
    private final SshExecutor ssh;
    private final List<RecipeDiscoverer> discoverers;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public DiscoveryService(MachineService machineService, RecipeService recipeService,
                            ActionService actionService, ApprovalService approvalService,
                            SshExecutor ssh, List<RecipeDiscoverer> discoverers,
                            PlatformTransactionManager transactionManager,
                            ApplicationEventPublisher events) {
        this.machineService = machineService;
        this.recipeService = recipeService;
        this.actionService = actionService;
        this.approvalService = approvalService;
        this.ssh = ssh;
        this.discoverers = discoverers;
        this.tx = new TransactionTemplate(transactionManager);
        this.events = events;
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
    public List<DiscoveredRecipe> discover(String machineId) {
        Machine machine = machineService.requireMachine(machineId);
        // Probe phase — no open transaction; collect proposals in memory.
        List<ProposedRecipe> proposals = new ArrayList<>();
        for (RecipeDiscoverer discoverer : discoverers) {
            proposals.addAll(discoverer.discover(machine, ssh));
        }
        // The probe phase connected over SSH — the box is reachable, so announce it;
        // a listener refreshes the machine to ONLINE asynchronously (via = SYSTEM).
        // spec-019.
        events.publishEvent(new MachineReachedEvent(machineId, Instant.now()));
        // Persist phase — one short transaction.
        return tx.execute(status -> persist(machineId, proposals));
    }

    private List<DiscoveredRecipe> persist(String machineId, List<ProposedRecipe> proposals) {
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
            discovered.add(new DiscoveredRecipe(recipe, actions));
        }
        return discovered;
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
}
