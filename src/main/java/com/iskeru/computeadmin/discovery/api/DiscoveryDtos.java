package com.iskeru.computeadmin.discovery.api;

import com.iskeru.computeadmin.discovery.service.DiscoveryService.DiscoveredRecipe;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.ReconcileOutcome;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.ReconciledAction;
import com.iskeru.computeadmin.recipe.api.RecipeDtos;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;

import java.util.List;

/**
 * DTO records for the {@code discovery} REST surface. A discovery run returns the
 * proposals it reconciled, each recipe with its actions. A not-yet-approved action
 * is {@code PENDING_APPROVAL} (its {@code pendingApproval} flag is set) so the UI
 * can present it for review; each action also carries the spec-021
 * {@link ReconcileOutcome} of this run, and — for an approved action discovery
 * would now change — the newly-proposed argv/params so the UI can show "review and
 * re-approve to adopt". Reuses the 004 {@link RecipeDtos} views; no mapper
 * framework.
 *
 * <p>spec-006; reconciliation outcome in spec-021.
 */
public final class DiscoveryDtos {

    private DiscoveryDtos() {
    }

    /** The proposals reconciled for a machine by a discovery run. */
    public record DiscoveryResult(String machineId, List<ProposedRecipeView> recipes) {
        public static DiscoveryResult of(String machineId, List<DiscoveredRecipe> discovered) {
            return new DiscoveryResult(machineId,
                    discovered.stream().map(ProposedRecipeView::of).toList());
        }
    }

    /** One reconciled recipe and its reconciled actions. */
    public record ProposedRecipeView(RecipeDtos.RecipeView recipe, List<ReconciledActionView> actions) {
        public static ProposedRecipeView of(DiscoveredRecipe discovered) {
            return new ProposedRecipeView(
                    RecipeDtos.RecipeView.of(discovered.recipe()),
                    discovered.actions().stream().map(ReconciledActionView::of).toList());
        }
    }

    /**
     * One action as reconciled by this discovery run: the action view, the
     * {@link ReconcileOutcome}, and (only when the outcome is
     * {@code DIFFERS_AWAITING_REAPPROVAL}) the newly-proposed argv/params discovery
     * would adopt if a human re-approved. {@code proposedArgTokens}/{@code
     * proposedParamDefs} are {@code null} for every other outcome.
     */
    public record ReconciledActionView(RecipeDtos.ActionView action, ReconcileOutcome outcome,
                                       List<ArgTokenInput> proposedArgTokens,
                                       List<ParamDefInput> proposedParamDefs) {
        public static ReconciledActionView of(ReconciledAction reconciled) {
            var proposed = reconciled.proposed();
            return new ReconciledActionView(
                    RecipeDtos.ActionView.of(reconciled.action()),
                    reconciled.outcome(),
                    proposed == null ? null : proposed.argTokens(),
                    proposed == null ? null : proposed.paramDefs());
        }
    }
}
