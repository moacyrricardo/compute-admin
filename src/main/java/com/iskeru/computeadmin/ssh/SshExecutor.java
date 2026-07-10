package com.iskeru.computeadmin.ssh;

import java.util.List;

/**
 * Port over the SSH transport: run a command on a target. Callers pass the command
 * as <strong>discrete argv</strong> — a list of already-separated arguments, never a
 * hand-built shell line — and each implementation is responsible for binding those
 * elements so a typed parameter stays one literal argument and cannot break out into
 * an injection (S4). {@code sudo} escalates via passwordless {@code sudo -n} (S5).
 *
 * <p>How the argv reaches the target differs by adapter, but the argv-in contract
 * and the injection-safety guarantee are the same: {@code LocalDevSshExecutor} hands
 * the argv straight to {@code ProcessBuilder} (true process arguments), while
 * {@code MinaSshExecutor} POSIX-single-quotes each element into the single command
 * string that SSH {@code exec} inherently runs through the remote shell.
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
