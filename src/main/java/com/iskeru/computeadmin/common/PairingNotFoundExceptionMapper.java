package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.auth.service.PairingNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link PairingNotFoundException} to a 404 JSON body {@code {"error":
 * "pairing_not_found"}}.
 *
 * <p>spec-011.
 */
@Provider
@Component
public class PairingNotFoundExceptionMapper implements ExceptionMapper<PairingNotFoundException> {

    @Override
    public Response toResponse(PairingNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "pairing_not_found"))
                .build();
    }
}
