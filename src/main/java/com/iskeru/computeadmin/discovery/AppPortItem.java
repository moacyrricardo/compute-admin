package com.iskeru.computeadmin.discovery;

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
 * <p>spec-025.
 */
public record AppPortItem(String appName, int port, String runtime) {
}
