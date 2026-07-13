package com.iskeru.computeadmin.monitor.model;

/**
 * What a monitored consumer <em>is</em>, so the fleet UI can slice by role
 * (spec-032 §3). An {@link #APP} is a service the user deploys and cares about
 * end-to-end; a {@link #DATABASE} is a datastore whose footprint is shared or
 * dedicated (see {@link Dedication}); {@link #OTHER} is anything else that owns
 * resource but is neither (a broker, a job runner, an unclassified process).
 *
 * <p>Populated from the labels the discoverers attach — native app-monitor
 * recipes map to {@link #APP} today; datastore/docker roles arrive with spec-033.
 *
 * <p>spec-032.
 */
public enum ConsumerRole {
    APP,
    DATABASE,
    OTHER
}
