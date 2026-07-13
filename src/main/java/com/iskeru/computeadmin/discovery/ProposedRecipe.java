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
 * <p>A docker compose monitor (spec-033) instead carries {@code dockerConsumers} — the
 * classified {@link DockerConsumer}s (project / datastore / bucket) the monitor read
 * surfaces as {@code MonitorConsumerView}s. Its {@code appPortList} stays empty (its
 * checks are fixed, project-scoped {@code docker} reads, not a per-port fan-out); the
 * two pre-fill channels are mutually exclusive on a given proposal.
 *
 * <p>spec-006; {@code appPortList} added in spec-025; {@code dockerConsumers} in spec-033.
 */
public record ProposedRecipe(RecipeType type, String name, String description,
                             List<ProposedAction> actions, List<AppPortItem> appPortList,
                             List<DockerConsumer> dockerConsumers) {

    /** A proposal with no pre-filled app list (host-vitals and non-monitor recipes). */
    public ProposedRecipe(RecipeType type, String name, String description, List<ProposedAction> actions) {
        this(type, name, description, actions, List.of(), List.of());
    }

    /** An app-monitor proposal with a pre-filled {@code (app-name, port)} list (spec-025). */
    public ProposedRecipe(RecipeType type, String name, String description,
                          List<ProposedAction> actions, List<AppPortItem> appPortList) {
        this(type, name, description, actions, appPortList, List.of());
    }

    /** A docker compose monitor proposal carrying classified consumers (spec-033). */
    public static ProposedRecipe ofDocker(String name, String description,
                                          List<ProposedAction> actions, List<DockerConsumer> consumers) {
        return new ProposedRecipe(RecipeType.MONITOR, name, description, actions, List.of(), consumers);
    }
}
