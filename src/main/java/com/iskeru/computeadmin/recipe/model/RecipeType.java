package com.iskeru.computeadmin.recipe.model;

/**
 * The service family a {@link Recipe} operates on. Display/grouping metadata and
 * the hint discovery (spec 006) tags a proposed recipe with; it does not itself
 * gate execution — the approval state does.
 *
 * <p>spec-004.
 */
public enum RecipeType {
    NGINX,
    DOCKER,
    DATABASE,
    CRON,
    CUSTOM
}
