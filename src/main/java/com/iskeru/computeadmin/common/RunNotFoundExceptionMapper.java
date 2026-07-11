package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.run.service.RunNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link RunNotFoundException} to a 404 JSON body {@code {"error":
 * "run_not_found"}}.
 *
 * <p>spec-005.
 */
@Provider
@Component
public class RunNotFoundExceptionMapper implements ExceptionMapper<RunNotFoundException> {

    @Override
    public Response toResponse(RunNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "run_not_found"))
                .build();
    }
}
