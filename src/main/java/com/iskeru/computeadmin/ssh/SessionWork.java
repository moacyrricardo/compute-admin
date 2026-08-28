package com.iskeru.computeadmin.ssh;

/**
 * The unit of work run inside an {@link SshExecutor#withSession} scope: it receives the
 * bound {@link SshSession} and may run any number of {@code exec}s on that one
 * connection, returning a value ({@code T}). spec-070.
 */
@FunctionalInterface
public interface SessionWork<T> {

    /** Runs against {@code session}; every {@code exec} reuses the one open connection. */
    T run(SshSession session);
}
