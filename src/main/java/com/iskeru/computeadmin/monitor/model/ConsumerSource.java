package com.iskeru.computeadmin.monitor.model;

/**
 * How a monitored consumer runs on its host (spec-032 §3): a {@link #NATIVE}
 * process (a systemd unit or a bare process, the spec-025 app-monitor path) or a
 * {@link #DOCKER} workload (a compose project / container). The source is read
 * from the {@code runtime} label the discoverer attaches — native discovery
 * stamps it today; docker compose consumers land with spec-033.
 *
 * <p>spec-032.
 */
public enum ConsumerSource {
    NATIVE,
    DOCKER
}
