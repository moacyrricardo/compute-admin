package com.iskeru.computeadmin.discovery.api;

import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.discovery.service.DiscoveryEnablementService.FamilyState;
import com.iskeru.computeadmin.recipe.api.RecipeDtos.ActionView;
import com.iskeru.computeadmin.recipe.api.RecipeDtos.RecipeView;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.Recipe;

import java.util.List;

/**
 * DTO records for the per-machine discovery-enablement surface (spec-035): the
 * {@code GET /api/machines/{id}/discovery} state (each family's toggle + the last
 * proposals) and the {@code PUT …/discovery/{family}} body. The proposals reuse the 004
 * {@link RecipeView}/{@link ActionView}; no mapper framework.
 *
 * <p>spec-035.
 */
public final class DiscoveryEnablementDtos {

    private DiscoveryEnablementDtos() {
    }

    /**
     * One discoverer family's toggle state: its enum {@code key}, a human {@code label},
     * whether it is currently {@code enabled} for this machine, its {@code defaultEnabled}
     * account-level default, and a one-line capability {@code note} (the docker
     * root-equivalence warning; {@code null} otherwise).
     */
    public record FamilyView(String key, String label, boolean enabled, boolean defaultEnabled,
                             String note) {
        public static FamilyView of(FamilyState state) {
            DiscovererFamily family = state.family();
            return new FamilyView(family.name(), family.displayName(), state.enabled(),
                    family.defaultEnabled(), family.note());
        }
    }

    /** One already-persisted proposal (a recipe and its actions) for the "last proposals" list. */
    public record ProposalView(RecipeView recipe, List<ActionView> actions) {
        public static ProposalView of(Recipe recipe, List<Action> actions) {
            return new ProposalView(RecipeView.of(recipe), actions.stream().map(ActionView::of).toList());
        }
    }

    /**
     * The discovery panel state for a machine: which families are enabled (in enum order)
     * and the proposals discovery has already persisted on it.
     */
    public record DiscoveryStateView(String machineId, List<FamilyView> families,
                                     List<ProposalView> proposals) {
    }

    /** {@code PUT /api/machines/{id}/discovery/{family}} body: enable or disable the family. */
    public record SetEnablementRequest(Boolean enabled) {
    }
}
