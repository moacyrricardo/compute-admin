package com.iskeru.computeadmin.machine.service;

/**
 * Thrown when a machine id does not exist or is not owned by the current user.
 * Maps to <strong>HTTP 404</strong> — existence is never leaked, so a not-owned
 * machine reads the same as an absent one.
 *
 * <p>spec-003.
 */
public class MachineNotFoundException extends RuntimeException {

    public MachineNotFoundException(String id) {
        super("Machine not found: " + id);
    }
}
