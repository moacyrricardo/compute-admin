package com.iskeru.computeadmin.run.service;

/**
 * Thrown when a run id is absent or owned by another user. Maps to
 * <strong>HTTP 404</strong> — existence is never leaked, so a not-owned run is
 * indistinguishable from a missing one.
 *
 * <p>spec-005.
 */
public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(String id) {
        super("Run not found: " + id);
    }
}
