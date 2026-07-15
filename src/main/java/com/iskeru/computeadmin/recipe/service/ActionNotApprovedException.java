package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when a caller tries to run an action that is not in the {@code APPROVED}
 * state. Maps to <strong>HTTP 409</strong> {@code {"error":"action_not_approved"}}.
 * The run path (spec-005) — shared by UI and MCP — raises this; it is the runtime
 * half of the gate that keeps an agent from executing an unapproved action.
 *
 * <p>spec-004; carries its own response since spec-046.
 */
public class ActionNotApprovedException extends AppException {

    public ActionNotApprovedException(String id) {
        super("Action not approved: " + id, Response.Status.CONFLICT, "action_not_approved");
    }
}
