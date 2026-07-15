package com.iskeru.computeadmin.run.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when a run id is absent or owned by another user. Maps to <strong>HTTP
 * 404</strong> {@code {"error":"run_not_found"}} — existence is never leaked, so a
 * not-owned run is indistinguishable from a missing one.
 *
 * <p>spec-005; carries its own response since spec-046.
 */
public class RunNotFoundException extends AppException {

    public RunNotFoundException(String id) {
        super(Response.Status.NOT_FOUND, "run_not_found");
    }
}
