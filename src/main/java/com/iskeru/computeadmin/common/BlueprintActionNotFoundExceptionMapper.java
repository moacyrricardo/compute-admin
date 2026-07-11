package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.blueprint.service.BlueprintActionNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link BlueprintActionNotFoundException} to a 404 JSON body {@code {"error":
 * "blueprint_action_not_found"}}.
 *
 * <p>spec-010.
 */
@Provider
@Component
public class BlueprintActionNotFoundExceptionMapper implements ExceptionMapper<BlueprintActionNotFoundException> {

    @Override
    public Response toResponse(BlueprintActionNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "blueprint_action_not_found"))
                .build();
    }
}
