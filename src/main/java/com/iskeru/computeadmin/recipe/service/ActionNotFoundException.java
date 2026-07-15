package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when an action id does not exist or is not owned by the current user
 * (ownership derives through {@code recipe.machine.owner}). Maps to <strong>HTTP
 * 404</strong> {@code {"error":"action_not_found"}} — existence is never leaked, so
 * a not-owned action reads the same as an absent one. This is also what an attempt
 * to approve another user's action surfaces as.
 *
 * <p>spec-004; carries its own response since spec-046.
 */
public class ActionNotFoundException extends AppException {

    public ActionNotFoundException(String id) {
        super("Action not found: " + id, Response.Status.NOT_FOUND, "action_not_found");
    }
}
