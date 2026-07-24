package com.iskeru.computeadmin.discovery.model;

/**
 * A group of {@link com.iskeru.computeadmin.discovery.RecipeDiscoverer}s that share a
 * single per-machine enablement toggle (spec-035). Enablement is decided per
 * <em>family</em>, not per probe: an operator turns "Docker discovery" on or off for a
 * machine, and every discoverer in that family is gated together.
 *
 * <p>Each family carries a {@code defaultEnabled} — the account-level default applied
 * to a machine that has no explicit {@link DiscoveryEnablement} row. Families that only
 * run reads the SSH login user already has (host vitals, native app discovery, the
 * {@code ss}/{@code /proc}-backed nginx/database/cron/systemd probes) default
 * <strong>on</strong>: this spec adds no friction to what already works. {@link #DOCKER}
 * defaults <strong>off</strong> because talking to the docker socket is
 * root-equivalent (concern-030, doubt 1) — it is an explicit opt-in per machine.
 *
 * <p>{@code note}, when present, is a one-line capability caveat the UI surfaces beside
 * the toggle (the docker root-equivalence warning); it is {@code null} for the
 * already-permitted families.
 *
 * <p>spec-035. Supersedes the interim {@code ca.discovery.docker.enabled} flag (spec-033).
 */
public enum DiscovererFamily {

    /** nginx site/config discovery — reads the login user already has. */
    NGINX("Nginx", true, null),

    /**
     * Docker container/compose discovery. <strong>Default off</strong>: the docker
     * socket is root-equivalent, so probing it is a capability decision, not a
     * read-you-could-already-do.
     */
    DOCKER("Docker", false, "Requires docker socket access; root-equivalent."),

    /** Database (mysql/postgres/…) discovery — reads the login user already has. */
    DATABASE("Database", true, null),

    /** Cron/scheduled-job discovery — reads the login user already has. */
    CRON("Cron", true, null),

    /** systemd unit discovery — reads the login user already has. */
    SYSTEMD("Systemd", true, null),

    /** Universal host-vitals monitor (spec-023) — reads the login user already has. */
    HOST("Host vitals", true, null),

    /** Native per-application monitor (spec-025) — reads the login user already has. */
    APP("Application monitor", true, null),

    /**
     * Unmanaged / script-launched app lifecycle discovery (spec-050) — the
     * script-launched counterpart of {@link #SYSTEMD}. Detects an app's lifecycle
     * scripts ({@code run.sh}/{@code stop.sh}/…) next to a running native app and
     * proposes them as gated {@code CUSTOM} start/stop/restart/deploy actions.
     * Default <strong>on</strong>: its probes are {@code /proc}/filesystem reads the
     * login user already has, and every proposal is gated like everything else.
     */
    LIFECYCLE("App lifecycle scripts", true, null);

    private final String displayName;
    private final boolean defaultEnabled;
    private final String note;

    DiscovererFamily(String displayName, boolean defaultEnabled, String note) {
        this.displayName = displayName;
        this.defaultEnabled = defaultEnabled;
        this.note = note;
    }

    /** Human-readable label for the UI toggle. */
    public String displayName() {
        return displayName;
    }

    /** Whether this family probes by default on a machine with no explicit override. */
    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    /** A one-line capability caveat for the UI, or {@code null} when there is none. */
    public String note() {
        return note;
    }
}
