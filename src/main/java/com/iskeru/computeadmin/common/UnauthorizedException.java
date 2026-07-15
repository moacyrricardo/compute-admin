package com.iskeru.computeadmin.common;

import jakarta.ws.rs.core.Response;

/**
 * Thrown when a request needs an authenticated caller but none is bound, or the
 * presented credentials do not verify. Maps to <strong>HTTP 401</strong> {@code
 * {"error":"unauthorized"}}. An optional message is kept for logs but never leaks
 * to the wire — every 401 body is the same generic shape, so existence and the
 * exact reason are never disclosed.
 *
 * <p>spec-011; carries its own response since spec-046.
 */
public class UnauthorizedException extends AppException {

    public UnauthorizedException() {
        super(Response.Status.UNAUTHORIZED, "unauthorized");
    }

    public UnauthorizedException(String message) {
        super(message, Response.Status.UNAUTHORIZED, "unauthorized");
    }
}
