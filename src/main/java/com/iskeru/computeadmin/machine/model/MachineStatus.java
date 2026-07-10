package com.iskeru.computeadmin.machine.model;

/**
 * Connection status of a registered {@link Machine}, refreshed by the
 * connectivity-check job. Starts {@link #UNKNOWN} until first probed.
 *
 * <p>spec-003.
 */
public enum MachineStatus {

    /** Not yet probed. */
    UNKNOWN,

    /** Last probe connected and the trivial command succeeded. */
    ONLINE,

    /** Reached, but the probe command exited non-zero. */
    OFFLINE,

    /** Could not connect/authenticate at all. */
    UNREACHABLE
}
