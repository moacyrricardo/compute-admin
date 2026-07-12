package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.auth.service.DuplicateEmailException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link DuplicateEmailException} to a 409 JSON body {@code {"error":
 * "email_taken"}}. One mapper per exception, per the convention.
 *
 * <p>spec-014.
 */
@Provider
@Component
public class DuplicateEmailExceptionMapper implements ExceptionMapper<DuplicateEmailException> {

    @Override
    public Response toResponse(DuplicateEmailException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "email_taken"))
                .build();
    }
}
