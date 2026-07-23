package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.AppPortItem;
import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
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
 * <p>Every app-monitor family also gains an app-level {@code cpu} check (spec-032): a
 * bounded, read-only process-tree CPU probe ({@link #CPU_PROBE_SCRIPT}), the first-class
 * CPU metric-kind the redesigned fleet UI reads alongside the process probe's RSS.
 *
 * <p>Every app-monitor family also gains an app-level {@code footprint} check (spec-049):
 * a fixed, read-only probe ({@link #FOOTPRINT_PROBE_SCRIPT}) that walks {@code /proc} + the
 * filesystem on the target and emits one NDJSON line — the app's folder, symlink identity,
 * and three distinct sizes (artifact {@code stat}, data grow-dir, {@code du} footprint on
 * deployed roots) — which {@code app.js} assembles onto the app card + drawer, feeding the
 * native disk story spec-039 left {@code —}. No backend model change (spec-040 thin-BE).
 *
 * <p>spec-025; the app-level CPU check added in spec-032; the footprint check in spec-049.
 */
@Component
public class AppMonitorDiscoverer implements RecipeDiscoverer {

    @Override
    public DiscovererFamily family() {
        return DiscovererFamily.APP;
    }

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

    /**
     * The fixed process-tree CPU probe (spec-032): from the listener port {@code $1}
     * resolve the owning PID(s) via {@code ss}, then read each PID's <em>and its direct
     * children's</em> {@code %cpu} via {@code ps} — the app's process tree (its PID plus
     * children), which covers gunicorn/uvicorn worker fan-out and a Spring Boot app's
     * helper processes. Bounded (a single {@code ps} level, no recursion), read-only, and
     * run as the login user (no {@code sudo}); the port is the sole positional argument,
     * never interpolated at authoring time (S4). Sampling is one-shot — {@code ps}'
     * {@code %cpu} is lifetime-average, and the shared-memory/backend double-count for a
     * multi-process app is summed naïvely; both caveats are documented, not solved, in v1
     * (spec-032 Known Gaps, spec-023 gap).
     */
    private static final String CPU_PROBE_SCRIPT = String.join("\n",
            "port=\"$1\"",
            "pids=$(ss -ltnpH 2>/dev/null | grep -E \":$port \" | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)",
            "[ -z \"$pids\" ] && { echo \"no listener on port $port\"; exit 0; }",
            "for pid in $pids; do",
            "  echo \"## pid $pid\"",
            "  ps -o pid=,ppid=,pcpu=,comm= -p \"$pid\" 2>/dev/null",
            "  ps -o pid=,ppid=,pcpu=,comm= --ppid \"$pid\" 2>/dev/null",
            "done");

    /**
     * The fixed app-folder &amp; footprint probe (spec-049): from the listener port
     * {@code $1} resolve the owning PID(s) via {@code ss}, pick the master PID (the
     * parent of the others — gunicorn/uvicorn workers inherit the socket — else the
     * lowest), then read {@code /proc/<pid>/} + walk the filesystem to learn where the
     * app lives and how big it is. Emits <strong>one NDJSON line</strong> (the spec-049
     * schema) with the resolved folder, symlink identity, a per-runtime detector
     * (java + python fully; node/go stubs) and three distinct sizes — the artifact
     * {@code stat}, a conventional data grow-dir, and a {@code du} footprint that runs
     * <em>only</em> for a deployed root ({@code timeout 10 du}; build trees are
     * suppressed as {@code footprintSkipped="build-tree"}). Absent/undeterminable ⇒ the
     * field is {@code null}, never fabricated; all string fields are JSON-escaped.
     *
     * <p>Purely read-only ({@code ss}/{@code readlink}/{@code stat}/{@code du}/{@code awk});
     * the port is the sole positional argument, never interpolated at authoring time (S4).
     * IO-heavy {@code du} is why the UI samples this probe on the slow poll tier only. Paths
     * ride the UI-only monitor read and never reach the MCP surface (S9).
     */
    private static final String FOOTPRINT_PROBE_SCRIPT = String.join("\n",
            "port=\"$1\"",
            "esc() { printf '%s' \"$1\" | sed 's/\\\\/\\\\\\\\/g; s/\"/\\\\\"/g'; }",
            "jstr() { if [ -z \"$1\" ]; then printf 'null'; else printf '\"%s\"' \"$(esc \"$1\")\"; fi; }",
            "jnum() { case \"$1\" in ''|*[!0-9]*) printf 'null' ;; *) printf '%s' \"$1\" ;; esac; }",
            "jarr() { if [ -z \"$1\" ]; then printf '[]'; else o=\"\"; for x in $1; do o=\"$o,\\\"$(esc \"$x\")\\\"\"; done; printf '[%s]' \"${o#,}\"; fi; }",
            "dusize() { timeout 10 du -sb --one-file-system \"$1\" 2>/dev/null | awk '{print $1; exit}'; }",
            "scandata() { for dd in data storage uploads media instance; do if [ -d \"$1/$dd\" ]; then dataDir=\"$1/$dd\"; dataBytes=$(dusize \"$1/$dd\"); return; fi; done; }",
            "pids=$(ss -ltnpH 2>/dev/null | grep -E \":$port \" | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -un)",
            "if [ -z \"$pids\" ]; then printf '{\"v\":1,\"port\":%s,\"rootKind\":\"unresolved\",\"footprintSkipped\":\"permission\",\"notes\":[\"no-listener-or-not-owner\"]}\\n' \"$port\"; exit 0; fi",
            "master=\"\"",
            "for p in $pids; do",
            "  for q in $pids; do",
            "    [ \"$p\" = \"$q\" ] && continue",
            "    qppid=$(awk '{print $4}' \"/proc/$q/stat\" 2>/dev/null)",
            "    [ \"$qppid\" = \"$p\" ] && { master=\"$p\"; break; }",
            "  done",
            "  [ -n \"$master\" ] && break",
            "done",
            "[ -z \"$master\" ] && master=$(printf '%s\\n' $pids | head -n1)",
            "pid=\"$master\"",
            "owner=$(stat -c%U \"/proc/$pid\" 2>/dev/null)",
            "cmdline=$(tr '\\0' ' ' < \"/proc/$pid/cmdline\" 2>/dev/null)",
            "argv0=$(tr '\\0' '\\n' < \"/proc/$pid/cmdline\" 2>/dev/null | head -n1)",
            "cwd=$(readlink -f \"/proc/$pid/cwd\" 2>/dev/null)",
            "exe=$(readlink -f \"/proc/$pid/exe\" 2>/dev/null)",
            "runtime=unknown",
            "case \"$argv0 $cmdline $exe\" in",
            "  *java*) runtime=java ;;",
            "  *python*|*gunicorn*|*uvicorn*|*celery*) runtime=python ;;",
            "  *node*) runtime=node ;;",
            "esac",
            "artifact=\"\"; artifactBytes=\"\"; appRoot=\"\"; link=\"\"; rootKind=unresolved",
            "buildTool=\"\"; markers=\"\"; dataDir=\"\"; dataBytes=\"\"; footprintBytes=\"\"; footprintSkipped=\"\"; notes=\"\"",
            "if [ -n \"$cwd\" ]; then appRoot=\"$cwd\"; rootKind=cwd-only; else footprintSkipped=permission; fi",
            "if [ \"$runtime\" = java ]; then",
            "  set -- $cmdline; jar=\"\"; prev=\"\"",
            "  for tok in \"$@\"; do [ \"$prev\" = \"-jar\" ] && { jar=\"$tok\"; break; }; prev=\"$tok\"; done",
            "  if [ -n \"$jar\" ]; then",
            "    rjar=$(readlink -f \"$jar\" 2>/dev/null); [ -z \"$rjar\" ] && rjar=\"$jar\"",
            "    [ \"$rjar\" != \"$jar\" ] && link=\"$jar\"",
            "    artifact=\"$rjar\"; artifactBytes=$(stat -c%s \"$rjar\" 2>/dev/null)",
            "    d=$(dirname \"$rjar\"); base=$(basename \"$d\")",
            "    if [ \"$base\" = target ] && [ -f \"$d/../pom.xml\" ]; then",
            "      buildTool=maven; appRoot=$(readlink -f \"$d/..\" 2>/dev/null); rootKind=build; markers=pom.xml; footprintSkipped=build-tree",
            "    elif [ \"$base\" = libs ] && [ \"$(basename \"$(dirname \"$d\")\")\" = build ] && { [ -f \"$d/../../build.gradle\" ] || [ -f \"$d/../../build.gradle.kts\" ] || [ -f \"$d/../../settings.gradle\" ] || [ -f \"$d/../../settings.gradle.kts\" ]; }; then",
            "      buildTool=gradle; appRoot=$(readlink -f \"$d/../..\" 2>/dev/null); rootKind=build; markers=build.gradle; footprintSkipped=build-tree",
            "    elif [ -f \"$d/pom.xml\" ] || [ -f \"$d/build.gradle\" ] || [ -f \"$d/build.gradle.kts\" ] || [ -f \"$d/settings.gradle\" ] || [ -d \"$d/src\" ]; then",
            "      appRoot=\"$d\"; rootKind=build; footprintSkipped=build-tree",
            "    else",
            "      appRoot=\"$d\"; rootKind=deploy",
            "    fi",
            "  else",
            "    appRoot=\"$cwd\"; [ -n \"$cwd\" ] && rootKind=cwd-only; notes=no-jar-cp-or-exploded",
            "  fi",
            "fi",
            "if [ \"$runtime\" = python ]; then",
            "  set -- $cmdline; entry=\"\"",
            "  case \"$cmdline\" in *manage.py*)",
            "    for tok in \"$@\"; do case \"$tok\" in */manage.py) entry=\"$tok\"; break ;; manage.py) entry=\"$cwd/manage.py\"; break ;; esac; done",
            "    buildTool=django ;; esac",
            "  if [ -z \"$entry\" ]; then for tok in \"$@\"; do case \"$tok\" in -*) continue ;; *:*) mod=${tok%%:*}; rel=$(printf '%s' \"$mod\" | tr '.' '/'); [ -f \"$cwd/$rel.py\" ] && entry=\"$cwd/$rel.py\"; break ;; esac; done; fi",
            "  if [ -z \"$entry\" ]; then for tok in \"$@\"; do case \"$tok\" in *.py) case \"$tok\" in /*) entry=\"$tok\" ;; *) entry=\"$cwd/$tok\" ;; esac; break ;; esac; done; fi",
            "  venv=\"\"",
            "  case \"$argv0\" in */bin/python*) vroot=$(dirname \"$(dirname \"$argv0\")\"); [ -f \"$vroot/pyvenv.cfg\" ] && venv=\"$vroot\" ;; esac",
            "  if [ -z \"$venv\" ]; then ve=$(tr '\\0' '\\n' < \"/proc/$pid/environ\" 2>/dev/null | grep '^VIRTUAL_ENV=' | head -n1 | cut -d= -f2-); [ -n \"$ve\" ] && [ -f \"$ve/pyvenv.cfg\" ] && venv=\"$ve\"; fi",
            "  sd=\"$cwd\"; [ -n \"$entry\" ] && sd=$(dirname \"$entry\")",
            "  root=\"\"; d=\"$sd\"; i=0",
            "  while [ \"$i\" -lt 5 ] && [ -n \"$d\" ] && [ \"$d\" != \"/\" ]; do",
            "    for mk in pyproject.toml setup.py setup.cfg requirements.txt Pipfile poetry.lock manage.py wsgi.py asgi.py; do [ -f \"$d/$mk\" ] && { root=\"$d\"; markers=\"$mk\"; break; }; done",
            "    [ -n \"$root\" ] && break",
            "    if [ -d \"$d/.venv\" ] || [ -d \"$d/venv\" ]; then root=\"$d\"; markers=.venv; break; fi",
            "    d=$(dirname \"$d\"); i=$((i + 1))",
            "  done",
            "  if [ -n \"$root\" ]; then appRoot=\"$root\"; rootKind=deploy",
            "  elif [ -n \"$venv\" ]; then vb=$(basename \"$venv\"); case \"$vb\" in .venv|venv|env) appRoot=$(dirname \"$venv\") ;; *) appRoot=\"$venv\" ;; esac; rootKind=deploy",
            "  elif [ -n \"$cwd\" ]; then appRoot=\"$cwd\"; rootKind=cwd-only; fi",
            "  [ -n \"$venv\" ] && notes=\"venv=$venv\"",
            "fi",
            "if [ \"$runtime\" = unknown ] && [ -n \"$exe\" ]; then",
            "  case \"$exe\" in *python*|*java*|*node*|*/sh|*/bash|*/dash) : ;; *) artifact=\"$exe\"; artifactBytes=$(stat -c%s \"$exe\" 2>/dev/null); runtime=go ;; esac",
            "fi",
            "if [ \"$rootKind\" = deploy ] && [ -n \"$appRoot\" ]; then",
            "  scandata \"$appRoot\"",
            "  fp=$(dusize \"$appRoot\")",
            "  if [ -n \"$fp\" ]; then footprintBytes=\"$fp\"; else footprintSkipped=timeout; fi",
            "fi",
            "printf '{\"v\":1,\"port\":%s,\"pid\":%s,\"user\":%s,\"runtime\":%s,\"cmdline\":%s,\"cwd\":%s,\"exe\":%s,\"artifact\":%s,\"artifactBytes\":%s,\"appRoot\":%s,\"link\":%s,\"rootKind\":%s,\"buildTool\":%s,\"markers\":%s,\"dataDir\":%s,\"dataBytes\":%s,\"footprintBytes\":%s,\"footprintSkipped\":%s,\"notes\":%s}\\n' \\",
            "  \"$port\" \"$(jnum \"$pid\")\" \"$(jstr \"$owner\")\" \"$(jstr \"$runtime\")\" \"$(jstr \"$cmdline\")\" \"$(jstr \"$cwd\")\" \"$(jstr \"$exe\")\" \"$(jstr \"$artifact\")\" \"$(jnum \"$artifactBytes\")\" \"$(jstr \"$appRoot\")\" \"$(jstr \"$link\")\" \"$(jstr \"$rootKind\")\" \"$(jstr \"$buildTool\")\" \"$(jarr \"$markers\")\" \"$(jstr \"$dataDir\")\" \"$(jnum \"$dataBytes\")\" \"$(jnum \"$footprintBytes\")\" \"$(jstr \"$footprintSkipped\")\" \"$(jarr \"$notes\")\"");

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
            String appName = appName(family, listener, cmdline, container, ssh, target);
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

    private String appName(Family family, Listener listener, String cmdline, String container,
                           SshExecutor ssh, SshTarget target) {
        if (container != null) {
            return sanitize(container, listener.port());
        }
        String derived = switch (family) {
            case SPRINGBOOT, HTTP -> springBootName(cmdline, ssh, target, listener.pid());
            case FASTAPI -> fastApiName(cmdline);
            case GENERIC -> listener.process();
        };
        return sanitize(derived, listener.port());
    }

    /** Generic jar/dir names too vague to label an app — trigger a deeper probe instead. */
    private static final java.util.Set<String> GENERIC_NAMES = java.util.Set.of(
            "app", "application", "web", "api", "server", "service", "main", "demo",
            "start", "run", "boot", "target", "build", "dist");

    private String springBootName(String cmdline, SshExecutor ssh, SshTarget target, String pid) {
        Matcher name = SPRING_APP_NAME.matcher(cmdline);
        if (name.find()) {
            return name.group(1);
        }
        // The executable jar is the token right AFTER `-jar` — not a `-cp`/`-classpath`
        // entry or a `-javaagent:`/`-agentpath:` jar (New Relic, OpenTelemetry, etc.),
        // which precede the main jar/class and would otherwise be picked as the app name.
        String jarPath = jarPathAfterDashJar(cmdline);
        String jarName = jarPath == null ? null : jarBaseName(jarPath);
        if (jarName != null && !isGeneric(jarName)) {
            return jarName;
        }
        // A generic (or missing) jar name — "app.jar", "web.jar". Reach further: the
        // deploy directory (cheap, /proc/<pid>/cwd) usually IS the app name, then the jar
        // manifest's Start-Class (the real app class; Main-Class is the boot loader).
        String cwd = deployDirName(ssh, target, pid);
        if (cwd != null && !isGeneric(cwd)) {
            return cwd;
        }
        String fromManifest = manifestAppName(ssh, target, jarPath);
        if (fromManifest != null) {
            return fromManifest;
        }
        return jarName; // fall back to the generic jar name (else sanitize → app-<port>)
    }

    private boolean isGeneric(String n) {
        return n == null || GENERIC_NAMES.contains(n.toLowerCase());
    }

    /** The path token right after {@code -jar}, or null (started via -cp + main class). */
    private String jarPathAfterDashJar(String cmdline) {
        String[] tokens = cmdline.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equals("-jar")) {
                return tokens[i + 1];
            }
        }
        return null;
    }

    /** {@code /opt/orders.jar} → {@code orders}. */
    private String jarBaseName(String jarPath) {
        Matcher jar = JAR.matcher(jarPath);
        return jar.find() ? jar.group(1) : null;
    }

    /** Basename of {@code /proc/<pid>/cwd} (the deploy dir), or null. */
    private String deployDirName(SshExecutor ssh, SshTarget target, String pid) {
        List<String> out = Probes.lines(ssh, target, List.of("readlink", "/proc/" + pid + "/cwd"));
        if (out.isEmpty()) {
            return null;
        }
        String path = out.get(0).trim();
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        return base.isEmpty() ? null : base;
    }

    /**
     * The app name from the jar's {@code META-INF/MANIFEST.MF} {@code Start-Class} (a
     * Spring Boot fat jar's real main class; its {@code Main-Class} is only the boot
     * loader), falling back to a non-loader {@code Main-Class} for a plain jar. Package
     * is dropped, a trailing {@code Application}/{@code Kt} stripped, camelCase → kebab:
     * {@code com.ex.BirthdayRsvpApplication} → {@code birthday-rsvp}. Null when {@code
     * unzip} is absent or nothing usable is found.
     */
    private String manifestAppName(SshExecutor ssh, SshTarget target, String jarPath) {
        // Only a plain jar path (no shell metacharacters) is passed, as an argv element.
        if (jarPath == null || !jarPath.matches("[A-Za-z0-9._/-]+\\.jar")) {
            return null;
        }
        List<String> raw = Probes.lines(ssh, target,
                List.of("unzip", "-p", jarPath, "META-INF/MANIFEST.MF"));
        if (raw.isEmpty()) {
            return null;
        }
        // Unfold MANIFEST continuations (a line starting with a space continues the prior).
        StringBuilder sb = new StringBuilder();
        for (String line : raw) {
            if (line.startsWith(" ") && sb.length() > 0) {
                sb.append(line, 1, line.length());
            } else {
                sb.append('\n').append(line);
            }
        }
        String startClass = null, mainClass = null;
        for (String line : sb.toString().split("\n")) {
            if (line.startsWith("Start-Class:")) {
                startClass = line.substring("Start-Class:".length()).trim();
            } else if (line.startsWith("Main-Class:")) {
                mainClass = line.substring("Main-Class:".length()).trim();
            }
        }
        String fqcn = startClass != null ? startClass : mainClass;
        if (fqcn == null || fqcn.startsWith("org.springframework.boot.loader")) {
            return null;
        }
        return classToAppName(fqcn);
    }

    /** {@code com.ex.BirthdayRsvpApplication} → {@code birthday-rsvp}. */
    private String classToAppName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        String simple = dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
        simple = simple.replaceAll("(Application|Kt)$", "");
        if (simple.isEmpty()) {
            return null;
        }
        String kebab = simple.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
        return kebab.isEmpty() ? null : kebab;
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
                    processProbe("process", "RSS/CPU/threads/fds from /proc (process-probe supplement)."),
                    cpuProbe(), footprintProbe());
            case FASTAPI -> fastApiActions(prometheus);
            case HTTP -> List.of(
                    endpointProbe("liveness", "HTTP liveness (GET / — no Actuator present).", "/"),
                    processProbe("process", "RSS/CPU/threads/fds from /proc (process-probe supplement)."),
                    cpuProbe(), footprintProbe());
            case GENERIC -> List.of(
                    processProbe("process", "RSS/CPU/threads/fds from /proc (process-probe only)."),
                    cpuProbe(), footprintProbe());
        };
        return new ProposedRecipe(RecipeType.MONITOR, family.recipeName, family.recipeDescription,
                actions, apps);
    }

    private List<ProposedAction> fastApiActions(boolean prometheus) {
        List<ProposedAction> actions = new ArrayList<>();
        actions.add(processProbe("process", "RSS/CPU/threads/fds from /proc across worker PIDs (always probed)."));
        actions.add(cpuProbe());
        actions.add(endpointProbe("health",
                "FastAPI liveness (default /openapi.json — is this app answering).", "/openapi.json"));
        if (prometheus) {
            actions.add(endpointProbe("metrics",
                    "Prometheus metrics (/metrics — proposed only when it responds).", "/metrics"));
        }
        actions.add(footprintProbe());
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

    /**
     * The app-level CPU check (spec-032): a fixed process-tree CPU probe driven by
     * {@link #CPU_PROBE_SCRIPT}, port as {@code $1}. Named {@code cpu} so it is the
     * first-class app CPU metric-kind the fleet UI reads (the host CPU vitals probe of
     * spec-023 is the host-level counterpart). Read-only, login-user, no {@code sudo} —
     * gated like every action.
     */
    private ProposedAction cpuProbe() {
        return new ProposedAction("cpu",
                "Process-tree CPU% (the app's PID plus children) via ps. Read-only.", false,
                List.of(literal("sh"), literal("-c"), literal(CPU_PROBE_SCRIPT),
                        literal("sh"), param(ParamBinder.PORT_COMPONENT)),
                List.of(appPortList(APP_LIST_PARAM)));
    }

    /**
     * The app-folder &amp; footprint check (spec-049): a fixed read-only probe driven by
     * {@link #FOOTPRINT_PROBE_SCRIPT}, port as {@code $1}. Named {@code footprint} so the
     * fleet UI parses its NDJSON line into the app's folder + artifact/data/footprint
     * sizes (spec-032 {@code cpu}-check precedent). Read-only, login-user, no {@code sudo}
     * — gated like every action; the only bound, fan-out-per-item param is the validated
     * port (S4-safe). Appended to every app-monitor family.
     */
    private ProposedAction footprintProbe() {
        return new ProposedAction("footprint",
                "App folder, artifact/data/footprint sizes from /proc + fs markers. Read-only.", false,
                List.of(literal("sh"), literal("-c"), literal(FOOTPRINT_PROBE_SCRIPT),
                        literal("sh"), param(ParamBinder.PORT_COMPONENT)),
                List.of(appPortList(APP_LIST_PARAM)));
    }

    /** One listening socket: its port, owning PID, and process name (login user only). */
    private record Listener(int port, String pid, String process) {
    }
}
