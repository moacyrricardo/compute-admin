package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import com.iskeru.computeadmin.recipe.service.RecipeService.CreateRecipeInput;
import com.iskeru.computeadmin.ssh.SshExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>spec-006.
 */
@Service
public class DiscoveryService {

    /** A persisted proposal: the pending recipe and its pending actions. */
    public record DiscoveredRecipe(Recipe recipe, List<Action> actions) {
    }

    private final MachineService machineService;
    private final RecipeService recipeService;
    private final ActionService actionService;
    private final ApprovalService approvalService;
    private final SshExecutor ssh;
    private final List<RecipeDiscoverer> discoverers;

    public DiscoveryService(MachineService machineService, RecipeService recipeService,
                            ActionService actionService, ApprovalService approvalService,
                            SshExecutor ssh, List<RecipeDiscoverer> discoverers) {
        this.machineService = machineService;
        this.recipeService = recipeService;
        this.actionService = actionService;
        this.approvalService = approvalService;
        this.ssh = ssh;
        this.discoverers = discoverers;
    }

    /**
     * Discovers and persists proposals for one of the current user's machines.
     *
     * @throws com.iskeru.computeadmin.machine.service.MachineNotFoundException 404 if
     *         the machine is absent or owned by another user.
     */
    @Transactional
    public List<DiscoveredRecipe> discover(String machineId) {
        Machine machine = machineService.requireMachine(machineId);
        List<DiscoveredRecipe> discovered = new ArrayList<>();
        for (RecipeDiscoverer discoverer : discoverers) {
            for (ProposedRecipe proposal : discoverer.discover(machine, ssh)) {
                Recipe recipe = recipeService.create(new CreateRecipeInput(
                        machineId, proposal.name(), proposal.description(), proposal.type()));
                List<Action> actions = new ArrayList<>();
                for (ProposedAction proposedAction : proposal.actions()) {
                    Action action = actionService.addAction(new AddActionInput(
                            recipe.getId(), proposedAction.name(), proposedAction.description(),
                            proposedAction.sudo(), proposedAction.argTokens(), proposedAction.paramDefs()));
                    // Benign DRAFT → PENDING_APPROVAL; never approve (approval is UI-only).
                    approvalService.submitForApproval(action.getId());
                    actions.add(action);
                }
                discovered.add(new DiscoveredRecipe(recipe, actions));
            }
        }
        return discovered;
    }
}
