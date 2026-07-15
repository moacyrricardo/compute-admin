package com.iskeru.computeadmin.blueprint.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when a blueprint id does not exist or is not owned by the current user
 * (blueprints are per-user). Maps to <strong>HTTP 404</strong> {@code
 * {"error":"blueprint_not_found"}} — existence is never leaked, so a not-owned
 * blueprint reads the same as an absent one.
 *
 * <p>spec-010; carries its own response since spec-046.
 */
public class BlueprintNotFoundException extends AppException {

    public BlueprintNotFoundException(String id) {
        super(Response.Status.NOT_FOUND, "blueprint_not_found");
    }
}
