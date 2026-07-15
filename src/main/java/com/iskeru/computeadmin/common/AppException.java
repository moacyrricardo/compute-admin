package com.iskeru.computeadmin.common;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Base for every domain exception that carries its own HTTP error {@link Response}.
 * Extending {@link WebApplicationException} means JAX-RS renders the embedded
 * response directly — so a subclass is a one-line {@code super(status, "code")} and
 * <strong>no {@code *ExceptionMapper} is needed</strong>. This replaces the 19
 * hand-written mappers with a single typed {@link ErrorResponse} body.
 *
 * <p>The wire format is unchanged from the deleted mappers: HTTP {@code status} +
 * {@code {"error":code}}, plus {@code {"detail":…}} for the SSH case.
 *
 * <p>spec-046.
 */
public class AppException extends WebApplicationException {

    /** {@code status} + {@code {"error":code}} — the 404/409/401/400 case. */
    public AppException(Response.Status status, String code) {
        super(Response.status(status).entity(ErrorResponse.of(code)).build());
    }

    /**
     * As {@code (status, code)}, but with an internal {@code message} kept on the
     * exception (for logs / {@code getMessage()}) that is <em>not</em> put on the
     * wire — the body stays {@code {"error":code}} with no {@code detail}. The
     * argument order (message first) distinguishes this from the detail-carrying
     * constructor below. Used where a code is a category but the throw site has a
     * more specific reason worth keeping off the wire (e.g. {@code
     * UnauthorizedException}'s "Invalid email or password").
     */
    protected AppException(String message, Response.Status status, String code) {
        super(message, Response.status(status).entity(ErrorResponse.of(code)).build());
    }

    /**
     * As above, plus a {@code {"detail":…}} field. The detail is also the
     * exception message, so callers that inspect {@code getMessage()} (e.g. run
     * output capture on an SSH failure) keep their existing text.
     */
    public AppException(Response.Status status, String code, String detail) {
        super(detail, Response.status(status).entity(new ErrorResponse(code, detail)).build());
    }
}
