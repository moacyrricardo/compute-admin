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
 * <p>spec-006.
 */
public record ProposedRecipe(RecipeType type, String name, String description, List<ProposedAction> actions) {
}
