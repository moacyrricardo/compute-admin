package com.iskeru.computeadmin.ssh;

import java.util.List;

/**
 * Port over the SSH transport: run a pre-assembled command on a target. The
 * command is passed as <strong>discrete argv</strong> and each element is bound
 * as a single argument — never string-concatenated into a shell line — so a typed
 * parameter cannot break out into an injection (S4). {@code sudo} escalates via
 * passwordless {@code sudo -n} (S5).
 *
 * <p>Business code depends on this port, never on MINA types. The real
 * {@code MinaSshExecutor} is the default bean; {@code LocalDevSshExecutor} swaps
 * in under the {@code localssh} profile.
 *
 * <p>spec-003.
 */
public interface SshExecutor {

    /** Runs {@code argv} on {@code target} and returns the captured result. */
    ExecResult exec(SshTarget target, List<String> argv, boolean sudo);

    /** Runs {@code argv} on {@code target}, streaming output to {@code sink} (spec 005). */
    void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink);
}
