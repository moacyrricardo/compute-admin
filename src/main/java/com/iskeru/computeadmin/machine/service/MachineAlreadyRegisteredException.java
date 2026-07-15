package com.iskeru.computeadmin.machine.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when the current user tries to register a machine that duplicates one
 * they already own (same host/port/login-user, the
 * {@code uq_machine_owner_host_port_user} key). Maps to <strong>HTTP 409</strong>
 * {@code {"error":"machine_already_registered"}} — a clean conflict instead of the
 * raw constraint-violation 500.
 *
 * <p>spec-003; carries its own response since spec-046.
 */
public class MachineAlreadyRegisteredException extends AppException {

    public MachineAlreadyRegisteredException(String host, int port, String loginUser) {
        super(Response.Status.CONFLICT, "machine_already_registered");
    }
}
