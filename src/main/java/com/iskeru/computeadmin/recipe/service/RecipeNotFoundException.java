package com.iskeru.computeadmin.recipe.service;

/**
 * Thrown when a recipe id does not exist or is not owned by the current user
 * (ownership derives through {@code machine.owner}). Maps to <strong>HTTP
 * 404</strong> — existence is never leaked, so a not-owned recipe reads the same
 * as an absent one.
 *
 * <p>spec-004.
 */
public class RecipeNotFoundException extends RuntimeException {

    public RecipeNotFoundException(String id) {
        super("Recipe not found: " + id);
    }
}
