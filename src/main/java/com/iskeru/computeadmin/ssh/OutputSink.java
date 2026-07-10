package com.iskeru.computeadmin.ssh;

/**
 * Callback for streaming command output: stdout/stderr chunks as they arrive and
 * a single completion with the exit code. The execution engine (spec 005) fans
 * these to the UI (SSE) and MCP (progress).
 *
 * <p>spec-003.
 */
public interface OutputSink {

    /** A chunk of stdout. */
    void onStdout(String chunk);

    /** A chunk of stderr. */
    void onStderr(String chunk);

    /** The command finished with {@code exitCode} (or {@code -1} if unreported). */
    void onComplete(int exitCode);
}
