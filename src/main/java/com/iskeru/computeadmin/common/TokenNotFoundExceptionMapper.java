package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.auth.service.TokenNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link TokenNotFoundException} to a 404 JSON body {@code {"error":
 * "token_not_found"}}.
 *
 * <p>spec-011.
 */
@Provider
@Component
public class TokenNotFoundExceptionMapper implements ExceptionMapper<TokenNotFoundException> {

    @Override
    public Response toResponse(TokenNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "token_not_found"))
                .build();
    }
}
