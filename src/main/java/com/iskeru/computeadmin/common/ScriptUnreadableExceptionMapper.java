package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.recipe.service.ScriptUnreadableException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link ScriptUnreadableException} to a 409 JSON body {@code {"error":
 * "script_unreadable"}} — the target's state prevents pinning a {@code CUSTOM}
 * action's script at approval.
 *
 * <p>spec-015.
 */
@Provider
@Component
public class ScriptUnreadableExceptionMapper implements ExceptionMapper<ScriptUnreadableException> {

    @Override
    public Response toResponse(ScriptUnreadableException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "script_unreadable"))
                .build();
    }
}
