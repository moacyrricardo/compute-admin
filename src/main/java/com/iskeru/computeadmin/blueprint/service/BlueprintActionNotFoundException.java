package com.iskeru.computeadmin.blueprint.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when a blueprint-action id does not exist or belongs to a blueprint not
 * owned by the current user (ownership derives through {@code blueprint.owner}).
 * Maps to <strong>HTTP 404</strong> {@code {"error":"blueprint_action_not_found"}}
 * — existence is never leaked.
 *
 * <p>spec-010; carries its own response since spec-046.
 */
public class BlueprintActionNotFoundException extends AppException {

    public BlueprintActionNotFoundException(String id) {
        super("Blueprint action not found: " + id, Response.Status.NOT_FOUND, "blueprint_action_not_found");
    }
}
