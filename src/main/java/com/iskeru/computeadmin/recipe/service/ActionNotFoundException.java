package com.iskeru.computeadmin.recipe.service;

/**
 * Thrown when an action id does not exist or is not owned by the current user
 * (ownership derives through {@code recipe.machine.owner}). Maps to <strong>HTTP
 * 404</strong> — existence is never leaked, so a not-owned action reads the same
 * as an absent one. This is also what an attempt to approve another user's action
 * surfaces as.
 *
 * <p>spec-004.
 */
public class ActionNotFoundException extends RuntimeException {

    public ActionNotFoundException(String id) {
        super("Action not found: " + id);
    }
}
