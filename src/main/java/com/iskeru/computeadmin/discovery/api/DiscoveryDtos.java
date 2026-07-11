package com.iskeru.computeadmin.discovery.api;

import com.iskeru.computeadmin.discovery.service.DiscoveryService.DiscoveredRecipe;
import com.iskeru.computeadmin.recipe.api.RecipeDtos;

import java.util.List;

/**
 * DTO records for the {@code discovery} REST surface. A discovery run returns the
 * proposals it persisted, each recipe with its actions — every action is
 * {@code PENDING_APPROVAL} (its {@code pendingApproval} flag is set) so the UI can
 * present them for review. Reuses the 004 {@link RecipeDtos} views; no mapper
 * framework.
 *
 * <p>spec-006.
 */
public final class DiscoveryDtos {

    private DiscoveryDtos() {
    }

    /** The proposals persisted for a machine by a discovery run. */
    public record DiscoveryResult(String machineId, List<ProposedRecipeView> recipes) {
        public static DiscoveryResult of(String machineId, List<DiscoveredRecipe> discovered) {
            return new DiscoveryResult(machineId,
                    discovered.stream().map(ProposedRecipeView::of).toList());
        }
    }

    /** One proposed recipe and its proposed (pending) actions. */
    public record ProposedRecipeView(RecipeDtos.RecipeView recipe, List<RecipeDtos.ActionView> actions) {
        public static ProposedRecipeView of(DiscoveredRecipe discovered) {
            return new ProposedRecipeView(
                    RecipeDtos.RecipeView.of(discovered.recipe()),
                    discovered.actions().stream().map(RecipeDtos.ActionView::of).toList());
        }
    }
}
