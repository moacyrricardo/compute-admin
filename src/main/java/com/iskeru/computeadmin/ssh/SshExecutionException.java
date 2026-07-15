package com.iskeru.computeadmin.ssh;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Wraps a failure to connect, authenticate, or run a command over SSH. When it
 * escapes a resource method (e.g. a discovery probe against an unreachable or
 * not-yet-authorized machine) it maps to a clean <strong>HTTP 502 Bad
 * Gateway</strong> {@code {"error":"ssh_failed","detail":…}} — the failure is in
 * the target box, not this service, so 502 is the honest status. The {@code detail}
 * (and {@code getMessage()}) is the {@code loginUser@host:port} target — the
 * operator's own machine, so not sensitive.
 *
 * <p>Runs already handle SSH failures internally (recorded as a FAILED run); the
 * 502 covers the paths that let the exception propagate — today, discovery.
 *
 * <p>spec-003; carries its own response since spec-046.
 */
public class SshExecutionException extends AppException {

    public SshExecutionException(SshTarget target, Throwable cause) {
        super(Response.Status.BAD_GATEWAY, "ssh_failed",
                "SSH command failed against " + target.loginUser() + "@" + target.host() + ":" + target.port());
    }
}
