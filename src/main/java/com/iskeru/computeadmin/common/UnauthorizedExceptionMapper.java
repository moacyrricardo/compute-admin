package com.iskeru.computeadmin.common;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link UnauthorizedException} to a 401 JSON body {@code {"error":
 * "unauthorized"}}. One mapper per exception, per the convention.
 *
 * <p>spec-011.
 */
@Provider
@Component
public class UnauthorizedExceptionMapper implements ExceptionMapper<UnauthorizedException> {

    @Override
    public Response toResponse(UnauthorizedException exception) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "unauthorized"))
                .build();
    }
}
