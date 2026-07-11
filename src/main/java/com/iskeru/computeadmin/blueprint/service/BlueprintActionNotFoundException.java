package com.iskeru.computeadmin.blueprint.service;

/**
 * Thrown when a blueprint-action id does not exist or belongs to a blueprint not
 * owned by the current user (ownership derives through {@code blueprint.owner}).
 * Maps to <strong>HTTP 404</strong> — existence is never leaked.
 *
 * <p>spec-010.
 */
public class BlueprintActionNotFoundException extends RuntimeException {

    public BlueprintActionNotFoundException(String id) {
        super("Blueprint action not found: " + id);
    }
}
