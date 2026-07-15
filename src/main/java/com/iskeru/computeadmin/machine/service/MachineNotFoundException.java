package com.iskeru.computeadmin.machine.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when a machine id does not exist or is not owned by the current user.
 * Maps to <strong>HTTP 404</strong> {@code {"error":"machine_not_found"}} —
 * existence is never leaked, so a not-owned machine reads the same as an absent one.
 *
 * <p>spec-003; carries its own response since spec-046.
 */
public class MachineNotFoundException extends AppException {

    public MachineNotFoundException(String id) {
        super("Machine not found: " + id, Response.Status.NOT_FOUND, "machine_not_found");
    }
}
