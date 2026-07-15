package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when a recipe id does not exist or is not owned by the current user
 * (ownership derives through {@code machine.owner}). Maps to <strong>HTTP
 * 404</strong> {@code {"error":"recipe_not_found"}} — existence is never leaked, so
 * a not-owned recipe reads the same as an absent one.
 *
 * <p>spec-004; carries its own response since spec-046.
 */
public class RecipeNotFoundException extends AppException {

    public RecipeNotFoundException(String id) {
        super(Response.Status.NOT_FOUND, "recipe_not_found");
    }
}
