package com.iskeru.computeadmin.recipe.service;

/**
 * Thrown when a caller tries to run an action that is not in the
 * {@code APPROVED} state. Maps to <strong>HTTP 409</strong>. The run path (spec
 * 005) — shared by UI and MCP — raises this; it is the runtime half of the gate
 * that keeps an agent from executing an unapproved action.
 *
 * <p>spec-004.
 */
public class ActionNotApprovedException extends RuntimeException {

    public ActionNotApprovedException(String id) {
        super("Action not approved: " + id);
    }
}
