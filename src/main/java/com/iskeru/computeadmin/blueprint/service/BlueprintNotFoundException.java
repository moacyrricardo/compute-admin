package com.iskeru.computeadmin.blueprint.service;

/**
 * Thrown when a blueprint id does not exist or is not owned by the current user
 * (blueprints are per-user). Maps to <strong>HTTP 404</strong> — existence is never
 * leaked, so a not-owned blueprint reads the same as an absent one.
 *
 * <p>spec-010.
 */
public class BlueprintNotFoundException extends RuntimeException {

    public BlueprintNotFoundException(String id) {
        super("Blueprint not found: " + id);
    }
}
