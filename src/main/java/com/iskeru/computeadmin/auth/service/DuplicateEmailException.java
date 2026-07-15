package com.iskeru.computeadmin.auth.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown by {@link AuthService#register} when the normalized email already belongs
 * to a user. A pre-check raises this so registration returns a clean <strong>HTTP
 * 409</strong> {@code {"error":"email_taken"}} rather than surfacing a raw
 * unique-constraint violation.
 *
 * <p>spec-014; carries its own response since spec-046.
 */
public class DuplicateEmailException extends AppException {

    public DuplicateEmailException(String email) {
        super("Email already registered: " + email, Response.Status.CONFLICT, "email_taken");
    }
}
