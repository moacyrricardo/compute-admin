package com.iskeru.computeadmin.ssh;

/**
 * Outcome of a one-shot remote command: the process exit code and its captured
 * stdout/stderr. Exit code {@code -1} means the remote never reported one.
 *
 * <p>spec-003.
 */
public record ExecResult(int exitCode, String stdout, String stderr) {

    /** {@code true} when the command exited zero. */
    public boolean succeeded() {
        return exitCode == 0;
    }
}
