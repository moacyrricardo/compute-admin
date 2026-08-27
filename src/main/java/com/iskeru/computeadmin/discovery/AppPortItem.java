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
 * <p>spec-025; context fields added in spec-055.
 */
public record AppPortItem(String appName, int port, String runtime,
                          String scriptFolder, String contextKey, String contextDisplay,
                          List<String> contextScripts) {

    public AppPortItem {
        contextScripts = contextScripts == null ? List.of() : List.copyOf(contextScripts);
    }

    /**
     * An item with no resolved context (existing call sites and un-mappable records). The
     * context fields default to {@code null}/empty.
     */
    public AppPortItem(String appName, int port, String runtime) {
        this(appName, port, runtime, null, null, null, List.of());
    }
}
