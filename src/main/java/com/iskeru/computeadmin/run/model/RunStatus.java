package com.iskeru.computeadmin.run.model;

/**
 * Lifecycle of a {@link Run}. A run is persisted {@link #QUEUED}, flips to
 * {@link #RUNNING} when the async task picks it up, and lands on a terminal
 * {@link #DONE} (exit 0), {@link #FAILED} (non-zero exit, or the transport
 * never reported one / raised an error), or {@link #INTERRUPTED} (the process
 * that owned the run died before it finished, so the boot reconciler resolved it).
 *
 * <p>spec-005; {@link #INTERRUPTED} added in spec-016; {@link #STOPPED} in spec-026.
 */
public enum RunStatus {

    /** Persisted and awaiting the async executor. */
    QUEUED,

    /** The async task has started the remote command. */
    RUNNING,

    /** The command finished with exit code 0. */
    DONE,

    /** The command exited non-zero, or the transport failed / reported no code. */
    FAILED,

    /**
     * Terminal. The run was left {@code QUEUED}/{@code RUNNING} by a previous
     * process that shut down (or crashed) before it finished, and the boot
     * reconciler ({@code RunReconciler}) marked it terminal. The remote command's
     * true outcome is unknown; {@code exitCode = -1}. spec-016.
     */
    INTERRUPTED,

    /**
     * Terminal. The owner cancelled a live {@code RUNNING} run through
     * {@code RunService.cancel(runId)} (e.g. to stop a follow-mode {@code -f} log
     * stream): the SSH channel was closed and the run marked here. {@code exitCode}
     * reflects the interrupted channel ({@code -1}); the captured output up to the
     * cancel is retained. spec-026.
     */
    STOPPED
}
