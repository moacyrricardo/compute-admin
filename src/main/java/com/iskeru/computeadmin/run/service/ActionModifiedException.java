package com.iskeru.computeadmin.run.service;

/**
 * Thrown when an action's <em>live</em> content hash no longer matches the
 * {@code approvedSnapshotHash} bound at approval — i.e. the action was mutated
 * after it was approved but before it ran. Maps to <strong>HTTP 409</strong>.
 *
 * <p>This is the run-time half of the TOCTOU defence: even though a structural
 * edit already resets approval to {@code DRAFT} (spec-004), the run path
 * re-verifies the hash so no drifted definition can ever execute (spec-005).
 *
 * <p>spec-005.
 */
public class ActionModifiedException extends RuntimeException {

    public ActionModifiedException(String id) {
        super("Action modified since approval: " + id);
    }
}
