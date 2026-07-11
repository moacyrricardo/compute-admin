package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.recipe.service.ActionNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link ActionNotFoundException} to a 404 JSON body {@code {"error":
 * "action_not_found"}}.
 *
 * <p>spec-004.
 */
@Provider
@Component
public class ActionNotFoundExceptionMapper implements ExceptionMapper<ActionNotFoundException> {

    @Override
    public Response toResponse(ActionNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "action_not_found"))
                .build();
    }
}
