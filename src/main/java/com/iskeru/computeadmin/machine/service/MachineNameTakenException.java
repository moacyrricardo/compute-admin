package com.iskeru.computeadmin.machine.service;

/**
 * Thrown when the current user tries to register a machine whose name duplicates
 * one they already own (the {@code uq_machine_owner_name} key). Maps to
 * <strong>HTTP 409</strong> — a clean conflict instead of the raw
 * constraint-violation 500. Name uniqueness is per-owner: two users may each own a
 * {@code web-prod-1}.
 *
 * <p>spec-028.
 */
public class MachineNameTakenException extends RuntimeException {

    public MachineNameTakenException(String name) {
        super("Machine name already in use: " + name);
    }
}
