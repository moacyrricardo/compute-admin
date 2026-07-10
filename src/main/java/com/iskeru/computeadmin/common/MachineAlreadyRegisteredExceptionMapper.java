package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.machine.service.MachineAlreadyRegisteredException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link MachineAlreadyRegisteredException} to a 409 JSON body {@code {"error":
 * "machine_already_registered"}}.
 *
 * <p>spec-003.
 */
@Provider
@Component
public class MachineAlreadyRegisteredExceptionMapper implements ExceptionMapper<MachineAlreadyRegisteredException> {

    @Override
    public Response toResponse(MachineAlreadyRegisteredException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "machine_already_registered"))
                .build();
    }
}
