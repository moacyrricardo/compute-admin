package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.recipe.service.ActionNotApprovedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link ActionNotApprovedException} to a 409 JSON body {@code {"error":
 * "action_not_approved"}}.
 *
 * <p>spec-004.
 */
@Provider
@Component
public class ActionNotApprovedExceptionMapper implements ExceptionMapper<ActionNotApprovedException> {

    @Override
    public Response toResponse(ActionNotApprovedException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "action_not_approved"))
                .build();
    }
}
