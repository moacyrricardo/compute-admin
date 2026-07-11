package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.recipe.service.ParamValidationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link ParamValidationException} to a 400 JSON body {@code {"error":
 * "param_validation_failed"}}.
 *
 * <p>spec-004.
 */
@Provider
@Component
public class ParamValidationExceptionMapper implements ExceptionMapper<ParamValidationException> {

    @Override
    public Response toResponse(ParamValidationException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "param_validation_failed"))
                .build();
    }
}
