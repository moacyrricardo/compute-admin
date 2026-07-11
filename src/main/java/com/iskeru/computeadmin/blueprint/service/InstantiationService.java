package com.iskeru.computeadmin.blueprint.service;

import com.iskeru.computeadmin.blueprint.model.BlueprintAction;
import com.iskeru.computeadmin.blueprint.model.BlueprintArgToken;
import com.iskeru.computeadmin.blueprint.model.BlueprintParamAllowedValue;
import com.iskeru.computeadmin.blueprint.model.BlueprintParamDef;
import com.iskeru.computeadmin.blueprint.model.RecipeBlueprint;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.service.ActionService;
import com.iskeru.computeadmin.recipe.service.ActionService.AddActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.EditActionInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.recipe.service.ActionSnapshot;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import jakarta.ws.rs.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies a {@link RecipeBlueprint} onto target machines as ordinary per-machine
 * {@code Recipe}/{@code Action} rows, recording provenance and preserving the core
 * invariant: <strong>instantiation never approves and never runs</strong>. It only
 * writes recipe/action config, so an instantiated action must still be approved
 * per-machine through the 004 UI gate — authoring is shared, approval never is.
 *
 * <p>Reconciliation (re-instantiating onto a machine that already has an instance):
 * <ul>
 *   <li>A brand-new action lands {@code PENDING_APPROVAL} (via
 *       {@code submitForApproval} — <em>not</em> approve), ready for a human.</li>
 *   <li>An existing action whose content (argv/params/sudo) changed is edited
 *       through {@link ActionService#editAction}, which resets it to {@code DRAFT}
 *       and clears its approval (the 004 content-hash TOCTOU rule) — so a blueprint
 *       change can never silently alter an already-approved action.</li>
 *   <li>An existing action whose content is unchanged is left untouched, so a prior
 *       approval survives a no-op re-instantiation.</li>
 * </ul>
 *
 * <p>All targets are resolved within the current user's own machines (explicit ids
 * 404 when not owned; a tag matches only the user's machines) — a user may
 * instantiate only onto his own fleet.
 *
 * <p>spec-010.
 */
@Service
public class InstantiationService {

    /**
     * Where to instantiate: exactly one of an explicit {@code machineIds} set
     * <strong>or</strong> a {@code tag} (resolved within the current user's
     * machines). Supplying both or neither is a 400.
     */
    public record InstantiateInput(Set<String> machineIds, String tag) {
    }

    /** One instantiated per-machine recipe and its resulting actions. */
    public record InstantiatedRecipe(Recipe recipe, List<Action> actions) {
    }

    private final BlueprintService blueprintService;
    private final MachineService machineService;
    private final RecipeService recipeService;
    private final ActionService actionService;
    private final ApprovalService approvalService;

    public InstantiationService(BlueprintService blueprintService,
                                MachineService machineService,
                                RecipeService recipeService,
                                ActionService actionService,
                                ApprovalService approvalService) {
        this.blueprintService = blueprintService;
        this.machineService = machineService;
        this.recipeService = recipeService;
        this.actionService = actionService;
        this.approvalService = approvalService;
    }

    /**
     * Instantiates (or reconciles) the blueprint onto every resolved target machine,
     * returning the per-machine recipe and its actions.
     */
    @Transactional
    public List<InstantiatedRecipe> instantiate(String blueprintId, InstantiateInput target) {
        RecipeBlueprint blueprint = blueprintService.requireBlueprint(blueprintId);
        List<BlueprintAction> blueprintActions = blueprintService.listActions(blueprintId);
        List<Machine> targets = resolveTargets(target);

        List<InstantiatedRecipe> results = new ArrayList<>();
        for (Machine machine : targets) {
            Recipe recipe = recipeService.findInstantiated(machine.getId(), blueprint.getId())
                    .map(existing -> recipeService.updateInstantiatedMeta(existing.getId(),
                            blueprint.getName(), blueprint.getDescription(), blueprint.getType(),
                            blueprint.getVersion()))
                    .orElseGet(() -> recipeService.createInstantiated(machine.getId(),
                            blueprint.getName(), blueprint.getDescription(), blueprint.getType(),
                            blueprint.getId(), blueprint.getVersion()));

            Map<String, Action> existingByName = new HashMap<>();
            for (Action action : recipeService.listActions(recipe.getId())) {
                existingByName.put(action.getName(), action);
            }

            for (BlueprintAction blueprintAction : blueprintActions) {
                Action existing = existingByName.get(blueprintAction.getName());
                if (existing == null) {
                    Action created = actionService.addAction(new AddActionInput(
                            recipe.getId(), blueprintAction.getName(), blueprintAction.getDescription(),
                            blueprintAction.isSudo(), tokenInputs(blueprintAction), paramInputs(blueprintAction)));
                    // DRAFT -> PENDING_APPROVAL. This is a submit, NOT an approve: the
                    // per-machine approval still happens only through the 004 UI gate.
                    approvalService.submitForApproval(created.getId());
                } else if (!desiredHash(blueprintAction).equals(ActionSnapshot.hash(existing))) {
                    // Content changed: editAction resets the action to DRAFT and clears
                    // its approval, so a changed blueprint can't ride an old approval.
                    actionService.editAction(existing.getId(), new EditActionInput(
                            blueprintAction.getName(), blueprintAction.getDescription(),
                            blueprintAction.isSudo(), tokenInputs(blueprintAction), paramInputs(blueprintAction)));
                }
                // else: content unchanged — leave the action (and any approval) as-is.
            }

            results.add(new InstantiatedRecipe(recipe, recipeService.listActions(recipe.getId())));
        }
        return results;
    }

    private List<Machine> resolveTargets(InstantiateInput target) {
        if (target == null) {
            throw new BadRequestException("target is required");
        }
        boolean hasTag = target.tag() != null && !target.tag().isBlank();
        boolean hasIds = target.machineIds() != null && !target.machineIds().isEmpty();
        if (hasTag == hasIds) {
            throw new BadRequestException("provide exactly one of machineIds or tag");
        }
        if (hasTag) {
            // Owner-scoped: matches only the current user's machines carrying the tag.
            return machineService.list(target.tag());
        }
        List<Machine> resolved = new ArrayList<>();
        for (String id : target.machineIds()) {
            // requireMachine 404s a machine the current user does not own.
            resolved.add(machineService.requireMachine(id));
        }
        return resolved;
    }

    private static List<ArgTokenInput> tokenInputs(BlueprintAction blueprintAction) {
        List<ArgTokenInput> inputs = new ArrayList<>();
        for (BlueprintArgToken token : blueprintAction.getArgTokens()) {
            inputs.add(new ArgTokenInput(token.getKind(), token.getValue()));
        }
        return inputs;
    }

    private static List<ParamDefInput> paramInputs(BlueprintAction blueprintAction) {
        List<ParamDefInput> inputs = new ArrayList<>();
        for (BlueprintParamDef def : blueprintAction.getParamDefs()) {
            List<String> allowed = new ArrayList<>();
            for (BlueprintParamAllowedValue value : def.getAllowedValues()) {
                allowed.add(value.getValue());
            }
            inputs.add(new ParamDefInput(def.getName(), def.getKind(), def.getPattern(),
                    def.getIntMin(), def.getIntMax(), allowed));
        }
        return inputs;
    }

    /**
     * The content hash the blueprint action would produce, computed over a transient
     * {@link Action} built from the blueprint's tokens/params/sudo so it can be
     * compared against an existing instance's {@link ActionSnapshot#hash(Action)}.
     * Never persisted.
     */
    private static String desiredHash(BlueprintAction blueprintAction) {
        Action transientAction = new Action();
        transientAction.setSudo(blueprintAction.isSudo());
        for (BlueprintArgToken token : blueprintAction.getArgTokens()) {
            ArgToken copy = new ArgToken();
            copy.setPosition(token.getPosition());
            copy.setKind(token.getKind());
            copy.setValue(token.getValue());
            transientAction.getArgTokens().add(copy);
        }
        for (BlueprintParamDef def : blueprintAction.getParamDefs()) {
            ParamDef copy = new ParamDef();
            copy.setName(def.getName());
            copy.setKind(def.getKind());
            copy.setPattern(def.getPattern());
            copy.setIntMin(def.getIntMin());
            copy.setIntMax(def.getIntMax());
            for (BlueprintParamAllowedValue value : def.getAllowedValues()) {
                ParamAllowedValue allowed = new ParamAllowedValue();
                allowed.setValue(value.getValue());
                copy.getAllowedValues().add(allowed);
            }
            transientAction.getParamDefs().add(copy);
        }
        return ActionSnapshot.hash(transientAction);
    }
}
