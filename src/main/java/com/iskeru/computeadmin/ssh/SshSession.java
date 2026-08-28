package com.iskeru.computeadmin.ssh;

import java.util.List;

/**
 * A bound execution scope over a single, already-authenticated SSH connection: every
 * {@link #exec} runs as a fresh channel on the <strong>one</strong> session opened by
 * {@link SshExecutor#withSession}. It carries no target argument because the session is
 * already bound to one — callers just hand it discrete argv (same S4 contract as
 * {@link SshExecutor#exec}). A transport failure surfaces as an
 * {@link SshExecutionException}.
 *
 * <p>The {@link #of(SshExecutor, SshTarget)} bridge realises a session as a per-call
 * reconnect over the plain port, so an adapter that does not override
 * {@code withSession} (dev/canned/test fakes) reuses its existing {@code exec} with no
 * code change; {@code MinaSshExecutor} overrides {@code withSession} to actually reuse
 * one live connection across the pass.
 *
 * <p>spec-070.
 */
@FunctionalInterface
public interface SshSession {

    /** Runs {@code argv} on this session's target and returns the captured result. */
    ExecResult exec(List<String> argv, boolean sudo);

    /**
     * A session that opens a fresh connection per {@code exec} over {@code ex} — the
     * default {@link SshExecutor#withSession} bridge (no session reuse).
     */
    static SshSession of(SshExecutor ex, SshTarget target) {
        return (argv, sudo) -> ex.exec(target, argv, sudo);
    }
}
