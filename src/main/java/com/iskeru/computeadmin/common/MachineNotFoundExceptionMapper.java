package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.machine.service.MachineNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link MachineNotFoundException} to a 404 JSON body {@code {"error":
 * "machine_not_found"}}.
 *
 * <p>spec-003.
 */
@Provider
@Component
public class MachineNotFoundExceptionMapper implements ExceptionMapper<MachineNotFoundException> {

    @Override
    public Response toResponse(MachineNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "machine_not_found"))
                .build();
    }
}
