package com.iskeru.computeadmin.ssh;

/**
 * Wraps a failure to connect, authenticate, or run a command over SSH. Unchecked
 * so the {@link SshExecutor} port stays exception-free in its signature; callers
 * (the connectivity job, the run engine) decide how to react — the job maps it to
 * an {@code UNREACHABLE} status.
 *
 * <p>spec-003.
 */
public class SshExecutionException extends RuntimeException {

    public SshExecutionException(SshTarget target, Throwable cause) {
        super("SSH command failed against " + target.loginUser() + "@" + target.host() + ":" + target.port(), cause);
    }
}
