package com.iskeru.computeadmin.monitor.model;

/**
 * The honest datastore axis (spec-032 §4), decided here so spec-033/034 agree. A
 * datastore is {@link #DEDICATED} when it belongs to exactly one app (a compose
 * project's own db): its resource is attributable, so an owner split is
 * legitimate. It is {@link #SHARED} when used by many apps with no single owner (a
 * native postgres, a standalone redis): a real footprint but <strong>no per-app
 * split</strong> — {@code usedBy} lists the consumers and {@code owner} is null.
 * {@code null} dedication means the consumer is not a datastore.
 *
 * <p>This is the distinction the databases lens (spec-034) draws.
 *
 * <p>spec-032.
 */
public enum Dedication {
    DEDICATED,
    SHARED
}
