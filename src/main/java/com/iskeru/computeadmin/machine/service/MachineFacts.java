package com.iskeru.computeadmin.machine.service;

/**
 * The tags a read-only probe detected about a reached machine: its operating system
 * family and, best-effort, the cloud it runs on. Either may be {@code null} when the
 * signal was absent or unrecognised. Consumed by
 * {@link MachineService#applyDetectedFacts(String, MachineFacts)}, which adds each
 * non-null value as an ordinary (get-or-created) tag.
 *
 * <p>spec-018.
 */
public record MachineFacts(String os, String cloud) {
}
