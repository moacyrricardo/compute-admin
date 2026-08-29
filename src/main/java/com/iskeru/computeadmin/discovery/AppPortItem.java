package com.iskeru.computeadmin.discovery;

import java.util.List;

/**
 * One discovery-classified app as a pre-filled {@code (app-name, port)} item on an
 * app-monitor recipe (spec-025), carrying the optional {@code runtime} label
 * (spec-022): {@code docker} when the app's PID resolved to a container (the
 * double-detection link to {@code DockerDiscoverer}), else {@code systemd} /
 * {@code process}.
 *
 * <p>Serialised to the recipe's {@code appPortList} as the same JSON object shape
 * {@code RunService} binds per fan-out item ({@code {"appName","port","runtime"}});
 * {@code appName} is validated at run time against the fixed
 * {@link com.iskeru.computeadmin.recipe.service.ParamBinder#APP_NAME_PATTERN} and
 * {@code port} against {@code [1, 65535]}, so a bad classification can never widen
 * the S4 surface — the human reviews the pre-filled list before approving.
 *
 * <p><strong>Context mapping (spec-055).</strong> When the record resolves to an owning
 * context ({@link com.iskeru.computeadmin.discovery.service.ContextMapper}), the item also
 * carries {@code scriptFolder} (the logical folder the app-script lives in), {@code
 * contextKey} (the D1 identity key — <strong>internal, S9-secret</strong>: never crosses the
 * MCP boundary as a path), {@code contextDisplay} (the logical context path the UI shows),
 * and {@code contextScripts} (the sibling app-scripts that collapse to the same context —
 * grouping metadata only, never argv, never hashed). All four are un-audited discovery
 * side-data, refreshed on every re-discovery. They are {@code null}/empty when the record
 * could not be mapped (e.g. a docker overlayfs path, which is 056's {@code docker inspect}
 * concern, not a host context).
 *
 * <p><strong>Provenance (spec-056).</strong> {@code sourceNote} is a short, human-readable
 * string naming the discovery <em>sweep branch</em> that produced the record — e.g.
 * {@code "app folder · discovered via port :8080"}, {@code "declared app · cron-launched ·
 * no port"}, {@code "compose project · discovered via docker"}. It is un-audited discovery
 * side-data for the 059 UI to render; it never carries a path (paths stay in the S9-secret
 * {@code contextKey}/{@code scriptFolder} fields) and is never argv or hashed.
 *
 * <p><strong>Non-listening apps (spec-056).</strong> A worker/cron/interpreter app that owns
 * no listening socket is emitted with the sentinel {@code port = 0} (the primitive
 * {@code int} has no null); the run-time {@code [1, 65535]} validator skips these
 * non-listening items (a 057 concern). {@code port = 0} together with a {@code sourceNote}
 * marks the record as declared-only.
 *
 * <p><strong>Fingerprint confidence (spec-056 Decision 5).</strong> {@code confidence} is
 * {@code "high"} when two signals agree that the record is a fingerprinted common service
 * (process/exe <em>and</em> port, or image <em>and</em> port), {@code "low"} on a single
 * signal, and {@code null} for a record that was not fingerprint-matched. Un-audited
 * side-data — a labelling hint, never argv or hashed.
 *
 * <p><strong>Management port (spec-073).</strong> {@code managementPort} is the separate
 * {@code management.server.port} an actuator-merged Spring Boot app answers actuator on, when
 * it differs from the {@code port} (the traffic port that is the record's identity). It is
 * {@code null} for the overwhelming single-port case, where every endpoint probe binds the own
 * {@code port}. Un-audited discovery side-data; the run path enriches it server-side per
 * {@code (appName, port)} so the actuator endpoint probes target the management port while the
 * process/footprint probes keep the traffic port.
 *
 * <p>spec-025; context fields added in spec-055; {@code sourceNote}/{@code confidence} in spec-056;
 * {@code managementPort} in spec-073.
 */
public record AppPortItem(String appName, int port, String runtime,
                          String scriptFolder, String contextKey, String contextDisplay,
                          List<String> contextScripts, String sourceNote, String confidence,
                          Integer managementPort) {

    public AppPortItem {
        contextScripts = contextScripts == null ? List.of() : List.copyOf(contextScripts);
    }

    /**
     * The pre-073 nine-field item (single-port app): no separate management port, so actuator
     * probes bind the own {@code port}. Kept so the docker/compose call sites are unchanged.
     */
    public AppPortItem(String appName, int port, String runtime,
                       String scriptFolder, String contextKey, String contextDisplay,
                       List<String> contextScripts, String sourceNote, String confidence) {
        this(appName, port, runtime, scriptFolder, contextKey, contextDisplay,
                contextScripts, sourceNote, confidence, null);
    }

    /**
     * An item with no resolved context (existing call sites and un-mappable records). The
     * context fields default to {@code null}/empty and no provenance/confidence is recorded.
     */
    public AppPortItem(String appName, int port, String runtime) {
        this(appName, port, runtime, null, null, null, List.of(), null, null, null);
    }
}
