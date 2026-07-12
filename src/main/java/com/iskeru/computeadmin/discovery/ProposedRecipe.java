package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.recipe.model.RecipeType;

import java.util.List;

/**
 * A recipe a {@link RecipeDiscoverer} proposes for a machine: its {@link RecipeType}
 * tag, a name, human-readable {@code description}, and the {@link ProposedAction}s
 * it groups. {@link DiscoveryService} turns each of these into a persisted
 * {@code Recipe} whose actions land in {@code PENDING_APPROVAL} — a proposal, never
 * an approved artefact.
 *
 * <p>An app-monitor recipe (spec-025) additionally carries {@code appPortList} — the
 * discovery-pre-filled {@code (app-name, port)} items its probe actions fan out over.
 * It is empty for host-vitals (spec-023) and every non-app-monitor proposal; the
 * four-arg constructor is the convenience for those.
 *
 * <p>spec-006; {@code appPortList} added in spec-025.
 */
public record ProposedRecipe(RecipeType type, String name, String description,
                             List<ProposedAction> actions, List<AppPortItem> appPortList) {

    /** A proposal with no pre-filled app list (host-vitals and non-monitor recipes). */
    public ProposedRecipe(RecipeType type, String name, String description, List<ProposedAction> actions) {
        this(type, name, description, actions, List.of());
    }
}
