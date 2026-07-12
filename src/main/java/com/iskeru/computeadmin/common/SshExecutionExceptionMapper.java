package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.ssh.SshExecutionException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps an {@link SshExecutionException} that escapes a resource method (e.g. a
 * discovery probe against an unreachable or not-yet-authorized machine) to a clean
 * <strong>502 Bad Gateway</strong> {@code {"error":"ssh_failed","detail":...}} rather
 * than letting it surface as an unmapped 500 with a stack trace. The failure is in
 * the target box (unreachable / key not installed / auth refused), not this service,
 * so 502 (upstream failure) is the honest status. The {@code detail} is the
 * exception's own {@code loginUser@host:port} message — the operator's own machine,
 * so not sensitive.
 *
 * <p>Runs already handle SSH failures internally (recorded as a FAILED run); this
 * mapper covers the paths that let the exception propagate — today, discovery.
 */
@Provider
@Component
public class SshExecutionExceptionMapper implements ExceptionMapper<SshExecutionException> {

    @Override
    public Response toResponse(SshExecutionException exception) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", "ssh_failed", "detail", String.valueOf(exception.getMessage())))
                .build();
    }
}
