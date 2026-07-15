package com.iskeru.computeadmin.auth.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when a personal token id does not exist or is not owned by the current
 * user. Maps to <strong>HTTP 404</strong> {@code {"error":"token_not_found"}} —
 * existence is never leaked, so a not-owned token reads the same as an absent one.
 *
 * <p>spec-011; carries its own response since spec-046.
 */
public class TokenNotFoundException extends AppException {

    public TokenNotFoundException(String id) {
        super("Personal token not found: " + id, Response.Status.NOT_FOUND, "token_not_found");
    }
}
