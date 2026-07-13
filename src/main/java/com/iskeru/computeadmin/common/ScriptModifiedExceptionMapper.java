package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.run.service.ScriptModifiedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link ScriptModifiedException} to a 409 JSON body {@code {"error":
 * "script_modified"}} — mirroring {@code ActionModifiedExceptionMapper}.
 *
 * <p>spec-015.
 */
@Provider
@Component
public class ScriptModifiedExceptionMapper implements ExceptionMapper<ScriptModifiedException> {

    @Override
    public Response toResponse(ScriptModifiedException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "script_modified"))
                .build();
    }
}
