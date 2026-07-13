package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.machine.service.MachineNameTakenException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link MachineNameTakenException} to a 409 JSON body {@code {"error":
 * "machine_name_taken"}}.
 *
 * <p>spec-028.
 */
@Provider
@Component
public class MachineNameTakenExceptionMapper implements ExceptionMapper<MachineNameTakenException> {

    @Override
    public Response toResponse(MachineNameTakenException exception) {
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "machine_name_taken"))
                .build();
    }
}
