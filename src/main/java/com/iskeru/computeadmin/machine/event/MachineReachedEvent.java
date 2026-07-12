package com.iskeru.computeadmin.machine.event;

import java.time.Instant;

/**
 * The first domain event in the codebase: "we just reached this machine over SSH".
 * Published by the service call sites that hold the {@code Machine} and observed a
 * successful SSH interaction — a run whose channel executed, a discovery probe that
 * connected, the connectivity probe on an ONLINE result, and the manual
 * test-connection endpoint. The low-level {@code SshExecutor} only sees a
 * host/port/login-user target, so it cannot publish; the callers do.
 *
 * <p>Decoupling the signal from its reaction lets any number of listeners react
 * without the SSH callers knowing about them: {@code MachineStatusUpdater} refreshes
 * the connection status to {@code ONLINE}, and later specs (018's facts probe /
 * auto-tagger) can subscribe to the same event.
 *
 * @param machineId the reached machine's id (never the host — the listener re-loads
 *                  the owned entity by id)
 * @param at        when the machine was reached
 *
 *                  <p>spec-019.
 */
public record MachineReachedEvent(String machineId, Instant at) {
}
