package com.iskeru.computeadmin.auth.service;

/**
 * Thrown when a personal token id does not exist or is not owned by the current
 * user. Maps to <strong>HTTP 404</strong> — existence is never leaked, so a
 * not-owned token reads the same as an absent one.
 *
 * <p>spec-011.
 */
public class TokenNotFoundException extends RuntimeException {

    public TokenNotFoundException(String id) {
        super("Personal token not found: " + id);
    }
}
