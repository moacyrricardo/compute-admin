package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.recipe.service.IllegalApprovalTransitionException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link IllegalApprovalTransitionException} to a 409 JSON body {@code
 * {"error": "illegal_approval_transition"}}.
 *
 * <p>spec-004.
 */
@Provider
@Component
public class IllegalApprovalTransitionExceptionMapper
        implements ExceptionMapper<IllegalApprovalTransitionException> {

    @Override
    public Response toResponse(IllegalApprovalTransitionException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "illegal_approval_transition"))
                .build();
    }
}
