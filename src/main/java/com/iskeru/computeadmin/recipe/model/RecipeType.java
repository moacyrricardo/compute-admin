package com.iskeru.computeadmin.recipe.model;

/**
 * The service family a {@link Recipe} operates on. Display/grouping metadata and
 * the hint discovery (spec 006) tags a proposed recipe with; it does not itself
 * gate execution — the approval state does.
 *
 * <p>{@link #MONITOR} tags a recipe that holds only monitor actions (host vitals or
 * app probes). Like every other value it is <strong>display/grouping metadata
 * only</strong>: it changes nothing about the gate — a {@code MONITOR} action runs
 * iff it is {@code APPROVED}, exactly like any action, with no read-only carve-out
 * and no auto-approval. Persisted {@code @Enumerated(STRING)}, so appending a value
 * is a non-breaking change (no ordinal renumber). spec-022.
 *
 * <p>spec-004; {@code MONITOR} added in spec-022.
 */
public enum RecipeType {
    NGINX,
    DOCKER,
    DATABASE,
    CRON,
    CUSTOM,
    MONITOR
}
