package com.iskeru.computeadmin.auth.service;

/**
 * Thrown by {@link AuthService#register} when the normalized email already belongs
 * to a user. A pre-check raises this so registration returns a clean 409 rather
 * than surfacing a raw unique-constraint violation. Maps to <strong>HTTP 409</strong>
 * via {@code DuplicateEmailExceptionMapper}.
 *
 * <p>spec-014.
 */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("Email already registered: " + email);
    }
}
