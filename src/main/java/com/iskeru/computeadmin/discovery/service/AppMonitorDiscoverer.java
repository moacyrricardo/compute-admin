package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.AppPortItem;
import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.iskeru.computeadmin.discovery.Proposals.appPortList;
import static com.iskeru.computeadmin.discovery.Proposals.literal;
import static com.iskeru.computeadmin.discovery.Proposals.param;

/**
 * Discovers the listening apps on a box and routes each to the matching app-monitor
 * recipe family (spec-025): <b>springboot monitor</b> (actuator endpoint probes +
 * a process-probe supplement), <b>fastapi monitor</b> (process probe + an
 * {@code /openapi.json} liveness probe + an optional {@code /metrics} probe when
 * Prometheus is present), and <b>generic app monitor</b> (process-probe only
 * fallback). All are {@link RecipeType#MONITOR}, approved once like any action — no
 * read-only carve-out, no auto-approval — with their probe actions fanning out over
 * the recipe's pre-filled {@code (app-name, port)} list (spec-022).
 *
 * <p><strong>Read-only, fixed probes.</strong> Classification runs only
 * {@code ss -ltnp} (fallback {@code netstat -ltnp}), {@code cat /proc/<pid>/cmdline}
 * / {@code /proc/<pid>/cgroup}, and — to detect Prometheus — a {@code curl -sf} GET.
 * It never issues a mutating command (spec-006 discoverer contract). It sees only the
 * <strong>login user's own</strong> sockets/processes (S5 gap): apps run by other
 * users are missed, by design — probe as the login user, no {@code sudo}.
 *
 * <p><strong>Probe templates are S4-safe.</strong> Each probe action is the
 * <em>fixed single-app template</em> the fan-out runs once per item (spec-022). The
 * only bound param is the validated {@code port}; the localhost host segment and the
 * endpoint path are fixed literals (probe loopback only — apps bind loopback; no
 * remote-target param). Because the token model cannot embed a validated {@code PARAM}
 * inside a single URL/`/proc` path argv element, the probe is realised as a
 * <strong>fixed {@code sh -c} script</strong> whose body is a source-controlled
 * constant and whose only positional argument ({@code $1}) is the validated port —
 * the same "fixed script template, only bound param is the port" shape the spec pins
 * for the process probe. The script string never varies per item, so the fan-out is
 * never a looping/variable shell command (S4 preserved per invocation).
 *
 * <p><strong>Double-detection link (spec-022).</strong> For each app PID the
 * classifier reads {@code /proc/<pid>/cgroup}; if it resolves to a container it
 * stamps {@code runtime = docker} and sets {@code appName} to the container name, so
 * the dashboard (spec-024) aggregates the health lens (here) and the lifecycle lens
 * ({@link DockerDiscoverer}) under one app card. {@code runtime = systemd} for a unit,
 * else {@code process}.
 *
 * <p>spec-025.
 */
@Component
public class AppMonitorDiscoverer implements RecipeDiscoverer {

    /** How each classified app is realised on the box (spec-022 label convention). */
    private enum Runtime {
        DOCKER("docker"), SYSTEMD("systemd"), PROCESS("process");

        private final String label;

        Runtime(String label) {
            this.label = label;
        }
    }

    /** A framework family and the recipe it routes to. */
    private enum Family {
        SPRINGBOOT("springboot monitor", "Discovered Spring Boot app health via Actuator, plus process metrics."),
        FASTAPI("fastapi monitor", "Discovered FastAPI app liveness/metrics, plus process metrics."),
        HTTP("http app monitor", "Discovered app liveness via HTTP (GET /, no actuator responded), plus process metrics."),
        GENERIC("generic app monitor", "Discovered app resource metrics from /proc (process probe only).");

        private final String recipeName;
        private final String recipeDescription;

        Family(String recipeName, String recipeDescription) {
            this.recipeName = recipeName;
            this.recipeDescription = recipeDescription;
        }
    }

    /** The single {@code APP_PORT_LIST} composite param every probe action declares. */
    private static final String APP_LIST_PARAM = "apps";

    /**
     * The fixed process-probe script (spec-025): from the listener port {@code $1}
     * resolve the owning PID(s) via {@code ss}, then read {@code /proc/<pid>/} for
     * RSS, threads, CPU jiffies, fd count and cmdline. gunicorn/uvicorn run several
     * worker PIDs per port, so it aggregates across every PID owning the port. Purely
     * read-only; the port is the sole positional argument, never interpolated into the
     * command string at authoring time (S4).
     */
    private static final String PROCESS_PROBE_SCRIPT = String.join("\n",
            "port=\"$1\"",
            "pids=$(ss -ltnpH 2>/dev/null | grep -E \":$port \" | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)",
            "[ -z \"$pids\" ] && { echo \"no listener on port $port\"; exit 0; }",
            "for pid in $pids; do",
            "  echo \"## pid $pid\"",
            "  tr '\\0' ' ' < \"/proc/$pid/cmdline\" 2>/dev/null; echo",
            "  grep -E '^(VmRSS|Threads):' \"/proc/$pid/status\" 2>/dev/null",
            "  awk '{print \"utime=\"$14\" stime=\"$15\" starttime=\"$22}' \"/proc/$pid/stat\" 2>/dev/null",
            "  ls \"/proc/$pid/fd\" 2>/dev/null | wc -l | sed 's/^/fds=/'",
            "done");

    /** ss line's owning process spec: {@code (("java",pid=1234,fd=10))}. */
    private static final Pattern SS_PROC = Pattern.compile("\\(\"([^\"]+)\",pid=(\\d+)");

    /** A jar path token on a java cmdline, e.g. {@code /opt/orders.jar}. */
    private static final Pattern JAR = Pattern.compile("([\\w.-]+)\\.jar");

    /** {@code -Dspring.application.name=orders}. */
    private static final Pattern SPRING_APP_NAME = Pattern.compile("-Dspring\\.application\\.name=([\\w.-]+)");

    /** A docker container reference inside a cgroup path, e.g. {@code /docker/orders} or {@code docker-<id>.scope}. */
    private static final Pattern CGROUP_DOCKER = Pattern.compile("docker[-/]([0-9A-Za-z_.-]+?)(?:\\.scope)?$");

    /** A 64/12-hex container id (as opposed to a human container name). */
    private static final Pattern HEX_ID = Pattern.compile("[0-9a-f]{12,}");

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        SshTarget target = Probes.target(machine);
        List<Listener> listeners = listeners(ssh, target);
        if (listeners.isEmpty()) {
            return List.of();
        }

        // Route each classified app into its family's pre-fill list. LinkedHashMap so
        // the springboot/fastapi/generic recipes propose in a stable order.
        Map<Family, List<AppPortItem>> byFamily = new LinkedHashMap<>();
        boolean prometheus = false;
        for (Listener listener : listeners) {
            String cmdline = cmdline(ssh, target, listener.pid());
            Runtime runtime = runtimeOf(ssh, target, listener.pid());
            String container = runtime == Runtime.DOCKER ? containerName(ssh, target, listener.pid()) : null;
            Family family = classify(listener.process(), cmdline);
            // A java listener is only a springboot monitor if Actuator actually answers;
            // otherwise its /actuator/* probes would all be dead. Fall back to an HTTP
            // liveness monitor (GET / + process) — the actuator-less Spring Boot case.
            if (family == Family.SPRINGBOOT && !respondsToActuator(ssh, target, listener.port())) {
                family = Family.HTTP;
            }
            String appName = appName(family, listener, cmdline, container);
            byFamily.computeIfAbsent(family, f -> new ArrayList<>())
                    .add(new AppPortItem(appName, listener.port(), runtime.label));
            if (family == Family.FASTAPI && respondsToMetrics(ssh, target, listener.port())) {
                prometheus = true;
            }
        }

        List<ProposedRecipe> proposals = new ArrayList<>();
        for (Map.Entry<Family, List<AppPortItem>> entry : byFamily.entrySet()) {
            proposals.add(recipeFor(entry.getKey(), entry.getValue(), prometheus));
        }
        return proposals;
    }

    // --- probes -------------------------------------------------------------

    /** Listening TCP sockets owned by the login user, via {@code ss} (netstat fallback). */
    private List<Listener> listeners(SshExecutor ssh, SshTarget target) {
        List<String> ss = Probes.lines(ssh, target, List.of("ss", "-ltnp"));
        if (!ss.isEmpty()) {
            return parseSs(ss);
        }
        return parseNetstat(Probes.lines(ssh, target, List.of("netstat", "-ltnp")));
    }

    /** The whitespace-normalised {@code /proc/<pid>/cmdline} (NUL-separated on disk). */
    private String cmdline(SshExecutor ssh, SshTarget target, String pid) {
        List<String> lines = Probes.lines(ssh, target, List.of("cat", "/proc/" + pid + "/cmdline"));
        return String.join(" ", lines).replace('\0', ' ').trim();
    }

    /** {@code docker} if the PID's cgroup resolves to a container, else systemd/process. */
    private Runtime runtimeOf(SshExecutor ssh, SshTarget target, String pid) {
        for (String line : Probes.lines(ssh, target, List.of("cat", "/proc/" + pid + "/cgroup"))) {
            if (line.contains("docker") || line.contains("kubepods") || line.contains("containerd")) {
                return Runtime.DOCKER;
            }
            if (line.contains(".service")) {
                return Runtime.SYSTEMD;
            }
        }
        return Runtime.PROCESS;
    }

    /** The container name recovered from the PID's cgroup, or {@code null} if only an opaque id. */
    private String containerName(SshExecutor ssh, SshTarget target, String pid) {
        for (String line : Probes.lines(ssh, target, List.of("cat", "/proc/" + pid + "/cgroup"))) {
            Matcher m = CGROUP_DOCKER.matcher(line);
            if (m.find()) {
                String ref = m.group(1);
                // A bare hex id can't be reconciled with a DockerDiscoverer name; skip it
                // so appName falls back to the cmdline-derived label.
                if (!HEX_ID.matcher(ref).matches()) {
                    return ref;
                }
            }
        }
        return null;
    }

    /** Whether the app answers Spring Boot Actuator on {@code /actuator/health} (HTTP 2xx). */
    private boolean respondsToActuator(SshExecutor ssh, SshTarget target, int port) {
        // Same fixed read-only GET shape as the metrics probe; -f makes a 404/redirect
        // fail so an actuator-less app yields no lines.
        return !Probes.lines(ssh, target,
                List.of("curl", "-sf", "-m", "2", "http://127.0.0.1:" + port + "/actuator/health")).isEmpty();
    }

    /** Whether the FastAPI app exposes a Prometheus {@code /metrics} endpoint (HTTP 2xx). */
    private boolean respondsToMetrics(SshExecutor ssh, SshTarget target, int port) {
        // Concrete integer port (from ss) built into a fixed read-only GET; -f makes a
        // 404 fail so a non-Prometheus app yields no lines.
        return !Probes.lines(ssh, target,
                List.of("curl", "-sf", "-m", "2", "http://127.0.0.1:" + port + "/metrics")).isEmpty();
    }

    // --- parsing ------------------------------------------------------------

    private List<Listener> parseSs(List<String> lines) {
        List<Listener> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.contains("LISTEN") || !line.contains("users:((")) {
                continue;
            }
            Integer port = localPort(line);
            Matcher m = SS_PROC.matcher(line);
            if (port != null && m.find()) {
                out.add(new Listener(port, m.group(2), m.group(1)));
            }
        }
        return out;
    }

    private List<Listener> parseNetstat(List<String> lines) {
        List<Listener> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.contains("LISTEN") || !line.contains("/")) {
                continue;
            }
            String[] tokens = line.trim().split("\\s+");
            Integer port = null;
            String pidProg = null;
            for (String token : tokens) {
                if (token.contains(":") && !token.contains("*")) {
                    port = portAfterColon(token);
                }
                if (token.matches("\\d+/.+")) {
                    pidProg = token;
                }
            }
            if (port != null && pidProg != null) {
                int slash = pidProg.indexOf('/');
                out.add(new Listener(port, pidProg.substring(0, slash), pidProg.substring(slash + 1)));
            }
        }
        return out;
    }

    /** The local port from an ss data line's "Local Address:Port" column. */
    private Integer localPort(String line) {
        for (String token : line.trim().split("\\s+")) {
            // Skip only the process column (users:((...))). Do NOT skip a "*:PORT"
            // token: an all-interfaces bind — the default for a JVM (Tomcat/Netty) —
            // renders the LOCAL address as "*:8080" in ss. The peer column "*:*" is
            // harmless here because portAfterColon returns null for a non-numeric tail.
            if (token.contains(":") && !token.startsWith("users:")) {
                Integer port = portAfterColon(token);
                if (port != null) {
                    return port;
                }
            }
        }
        return null;
    }

    private Integer portAfterColon(String addr) {
        String tail = addr.substring(addr.lastIndexOf(':') + 1);
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- classification -----------------------------------------------------

    private Family classify(String process, String cmdline) {
        String haystack = (process + " " + cmdline).toLowerCase();
        if (haystack.contains("uvicorn") || haystack.contains("gunicorn")) {
            return Family.FASTAPI;
        }
        if (haystack.contains("java")) {
            return Family.SPRINGBOOT;
        }
        return Family.GENERIC;
    }

    private String appName(Family family, Listener listener, String cmdline, String container) {
        if (container != null) {
            return sanitize(container, listener.port());
        }
        String derived = switch (family) {
            case SPRINGBOOT, HTTP -> springBootName(cmdline);
            case FASTAPI -> fastApiName(cmdline);
            case GENERIC -> listener.process();
        };
        return sanitize(derived, listener.port());
    }

    private String springBootName(String cmdline) {
        Matcher name = SPRING_APP_NAME.matcher(cmdline);
        if (name.find()) {
            return name.group(1);
        }
        Matcher jar = JAR.matcher(cmdline);
        return jar.find() ? jar.group(1) : null;
    }

    private String fastApiName(String cmdline) {
        // "uvicorn orders.main:app" / "gunicorn orders.wsgi" → the module before ':'/'.'.
        for (String token : cmdline.split("\\s+")) {
            if (token.contains(":") && !token.startsWith("-")) {
                String module = token.substring(0, token.indexOf(':'));
                int dot = module.indexOf('.');
                return dot > 0 ? module.substring(0, dot) : module;
            }
        }
        return null;
    }

    /** Coerce a raw label to the fixed app-name charset; fall back to {@code app-<port>}. */
    private String sanitize(String raw, int port) {
        if (raw != null) {
            String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^-+|-+$", "");
            if (!cleaned.isEmpty()) {
                return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
            }
        }
        return "app-" + port;
    }

    // --- recipe assembly ----------------------------------------------------

    private ProposedRecipe recipeFor(Family family, List<AppPortItem> apps, boolean prometheus) {
        List<ProposedAction> actions = switch (family) {
            case SPRINGBOOT -> List.of(
                    endpointProbe("health", "Spring Boot liveness/readiness (/actuator/health).", "/actuator/health"),
                    endpointProbe("metrics", "JVM + HTTP metrics (/actuator/metrics).", "/actuator/metrics"),
                    endpointProbe("beans", "Wired beans (/actuator/beans).", "/actuator/beans"),
                    endpointProbe("info", "Build/runtime facts (/actuator/info).", "/actuator/info"),
                    processProbe("process", "RSS/CPU/threads/fds from /proc (process-probe supplement)."));
            case FASTAPI -> fastApiActions(prometheus);
            case HTTP -> List.of(
                    endpointProbe("liveness", "HTTP liveness (GET / — no Actuator present).", "/"),
                    processProbe("process", "RSS/CPU/threads/fds from /proc (process-probe supplement)."));
            case GENERIC -> List.of(
                    processProbe("process", "RSS/CPU/threads/fds from /proc (process-probe only)."));
        };
        return new ProposedRecipe(RecipeType.MONITOR, family.recipeName, family.recipeDescription,
                actions, apps);
    }

    private List<ProposedAction> fastApiActions(boolean prometheus) {
        List<ProposedAction> actions = new ArrayList<>();
        actions.add(processProbe("process", "RSS/CPU/threads/fds from /proc across worker PIDs (always probed)."));
        actions.add(endpointProbe("health",
                "FastAPI liveness (default /openapi.json — is this app answering).", "/openapi.json"));
        if (prometheus) {
            actions.add(endpointProbe("metrics",
                    "Prometheus metrics (/metrics — proposed only when it responds).", "/metrics"));
        }
        return actions;
    }

    /**
     * A fixed endpoint-probe action: {@code sh -c 'curl -s -m 2 "http://127.0.0.1:$1<path>"' sh <port>}.
     * The script body (host + path) is a source-controlled constant; the only bound,
     * fan-out-per-item param is the validated {@code port} passed as {@code $1}.
     */
    private ProposedAction endpointProbe(String name, String description, String path) {
        String script = "curl -s -m 2 \"http://127.0.0.1:$1" + path + "\"";
        return new ProposedAction(name, description, false,
                List.of(literal("sh"), literal("-c"), literal(script),
                        literal("sh"), param(ParamBinder.PORT_COMPONENT)),
                List.of(appPortList(APP_LIST_PARAM)));
    }

    /** A fixed process-probe action driven by {@link #PROCESS_PROBE_SCRIPT}, port as {@code $1}. */
    private ProposedAction processProbe(String name, String description) {
        return new ProposedAction(name, description, false,
                List.of(literal("sh"), literal("-c"), literal(PROCESS_PROBE_SCRIPT),
                        literal("sh"), param(ParamBinder.PORT_COMPONENT)),
                List.of(appPortList(APP_LIST_PARAM)));
    }

    /** One listening socket: its port, owning PID, and process name (login user only). */
    private record Listener(int port, String pid, String process) {
    }
}
