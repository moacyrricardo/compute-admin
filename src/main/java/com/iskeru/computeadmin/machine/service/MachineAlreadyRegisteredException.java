package com.iskeru.computeadmin.machine.service;

/**
 * Thrown when the current user tries to register a machine that duplicates one
 * they already own (same host/port/login-user, the
 * {@code uq_machine_owner_host_port_user} key). Maps to <strong>HTTP 409</strong>
 * — a clean conflict instead of the raw constraint-violation 500.
 *
 * <p>spec-003.
 */
public class MachineAlreadyRegisteredException extends RuntimeException {

    public MachineAlreadyRegisteredException(String host, int port, String loginUser) {
        super("Machine already registered: " + loginUser + "@" + host + ":" + port);
    }
}
