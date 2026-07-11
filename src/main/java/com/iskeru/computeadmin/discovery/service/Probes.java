package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;

import java.util.List;

/**
 * Shared read-only probe primitives for the discoverers: build the {@link SshTarget}
 * from a machine, test for a binary, and read a command's stdout as trimmed lines.
 * Every probe runs <strong>without sudo</strong> and is a fixed, source-controlled
 * command — the spec-006 invariant that discovery never mutates the box. Pure
 * helper (no suffix, no state).
 *
 * <p>spec-006.
 */
final class Probes {

    private Probes() {
    }

    /** The SSH target for a machine (app keypair authenticates, spec-003). */
    static SshTarget target(Machine machine) {
        return new SshTarget(machine.getHost(), machine.getPort(), machine.getLoginUser());
    }

    /** {@code true} when {@code binary} resolves on the target ({@code command -v}). */
    static boolean commandExists(SshExecutor ssh, SshTarget target, String binary) {
        ExecResult result = ssh.exec(target, List.of("command", "-v", binary), false);
        return result.succeeded() && result.stdout() != null && !result.stdout().isBlank();
    }

    /** The trimmed, non-blank stdout lines of {@code argv}; empty when it fails. */
    static List<String> lines(SshExecutor ssh, SshTarget target, List<String> argv) {
        ExecResult result = ssh.exec(target, argv, false);
        if (!result.succeeded() || result.stdout() == null) {
            return List.of();
        }
        return result.stdout().lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
