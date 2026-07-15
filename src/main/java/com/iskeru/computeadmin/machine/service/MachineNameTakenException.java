package com.iskeru.computeadmin.machine.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when the current user tries to register a machine whose name duplicates
 * one they already own (the {@code uq_machine_owner_name} key). Maps to
 * <strong>HTTP 409</strong> {@code {"error":"machine_name_taken"}} — a clean
 * conflict instead of the raw constraint-violation 500. Name uniqueness is
 * per-owner: two users may each own a {@code web-prod-1}.
 *
 * <p>spec-028; carries its own response since spec-046.
 */
public class MachineNameTakenException extends AppException {

    public MachineNameTakenException(String name) {
        super("Machine name already in use: " + name, Response.Status.CONFLICT, "machine_name_taken");
    }
}
