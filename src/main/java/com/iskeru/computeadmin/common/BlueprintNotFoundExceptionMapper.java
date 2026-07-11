package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.blueprint.service.BlueprintNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link BlueprintNotFoundException} to a 404 JSON body {@code {"error":
 * "blueprint_not_found"}}.
 *
 * <p>spec-010.
 */
@Provider
@Component
public class BlueprintNotFoundExceptionMapper implements ExceptionMapper<BlueprintNotFoundException> {

    @Override
    public Response toResponse(BlueprintNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "blueprint_not_found"))
                .build();
    }
}
