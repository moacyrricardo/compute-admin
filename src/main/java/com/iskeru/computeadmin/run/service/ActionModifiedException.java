package com.iskeru.computeadmin.run.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when an action's <em>live</em> content hash no longer matches the
 * {@code approvedSnapshotHash} bound at approval — i.e. the action was mutated
 * after it was approved but before it ran. Maps to <strong>HTTP 409</strong>
 * {@code {"error":"action_modified"}}.
 *
 * <p>This is the run-time half of the TOCTOU defence: even though a structural
 * edit already resets approval to {@code DRAFT} (spec-004), the run path
 * re-verifies the hash so no drifted definition can ever execute (spec-005).
 *
 * <p>spec-005; carries its own response since spec-046.
 */
public class ActionModifiedException extends AppException {

    public ActionModifiedException(String id) {
        super("Action modified since approval: " + id, Response.Status.CONFLICT, "action_modified");
    }
}
