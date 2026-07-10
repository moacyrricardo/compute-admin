package com.iskeru.computeadmin.common;

/**
 * Thrown when a request needs an authenticated caller but none is bound. Maps to
 * <strong>HTTP 401</strong> via {@code UnauthorizedExceptionMapper}.
 *
 * <p>spec-011.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException() {
        super("Authentication required");
    }

    public UnauthorizedException(String message) {
        super(message);
    }
}
