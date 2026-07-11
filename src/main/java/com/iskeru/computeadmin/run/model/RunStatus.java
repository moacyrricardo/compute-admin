package com.iskeru.computeadmin.run.model;

/**
 * Lifecycle of a {@link Run}. A run is persisted {@link #QUEUED}, flips to
 * {@link #RUNNING} when the async task picks it up, and lands on a terminal
 * {@link #DONE} (exit 0) or {@link #FAILED} (non-zero exit, or the transport
 * never reported one / raised an error).
 *
 * <p>spec-005.
 */
public enum RunStatus {

    /** Persisted and awaiting the async executor. */
    QUEUED,

    /** The async task has started the remote command. */
    RUNNING,

    /** The command finished with exit code 0. */
    DONE,

    /** The command exited non-zero, or the transport failed / reported no code. */
    FAILED
}
