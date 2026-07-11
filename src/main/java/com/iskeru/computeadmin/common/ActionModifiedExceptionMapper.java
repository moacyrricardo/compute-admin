package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.run.service.ActionModifiedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link ActionModifiedException} to a 409 JSON body {@code {"error":
 * "action_modified"}}.
 *
 * <p>spec-005.
 */
@Provider
@Component
public class ActionModifiedExceptionMapper implements ExceptionMapper<ActionModifiedException> {

    @Override
    public Response toResponse(ActionModifiedException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "action_modified"))
                .build();
    }
}
