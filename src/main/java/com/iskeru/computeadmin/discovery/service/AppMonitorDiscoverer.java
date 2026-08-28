package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.AppPortItem;
import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.ssh.SshSession;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>spec-025; the app-level CPU check added in spec-032.
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

    /** The fixed interpreter set the non-listening scan recognises (spec-056 Decision 3). */
    private static final Set<String> INTERPRETERS = Set.of(
            "python", "python3", "node", "ruby", "php", "java", "perl", "bash", "sh");

    /** A script-path token that looks like an interpreter's file argument (not a flag/module). */
    private static final Pattern SCRIPT_ARG = Pattern.compile(".*\\.(py|js|rb|php|pl|sh|jar)$");

    /** A well-formed systemd service unit name (the only bound input to `systemctl show`, S4). */
    private static final Pattern SERVICE_UNIT = Pattern.compile("[A-Za-z0-9@._-]+\\.service");

    /**
     * The fixed cron-enumeration script (spec-056 Decision 3): read every cron source the login
     * user can see — its own {@code crontab -l}, {@code /etc/crontab}, {@code /etc/cron.d/*} and
     * the {@code /etc/cron.{daily,hourly,weekly,monthly}} run-parts dirs — and print their lines.
     * The body is a source-controlled constant with no bound input, so it is trivially S4-safe;
     * it only reads (never {@code crontab -e}/install), the spec-006 read-only contract.
     */
    private static final String CRON_PROBE_SCRIPT = String.join("\n",
            "crontab -l 2>/dev/null",
            "cat /etc/crontab 2>/dev/null",
            "cat /etc/cron.d/* 2>/dev/null",
            "for d in /etc/cron.daily /etc/cron.hourly /etc/cron.weekly /etc/cron.monthly; do",
            "  for f in \"$d\"/*; do [ -f \"$f\" ] && echo \"$f\"; done",
            "done");

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshSession session) {
        List<Listener> listeners = listeners(session);

        // Classify each app and map it to its owning context (spec-055). Two passes: first
        // resolve every listener, then group by contextKey so a context can enumerate the
        // sibling app-scripts collapsing to it (grouping metadata only, spec-055 D4).
        List<Resolved> resolved = new ArrayList<>();
        Set<String> listeningPids = new HashSet<>();
        boolean prometheus = false;
        for (Listener listener : listeners) {
            // Decision 4: a published container port may be owned on the host by
            // `docker-proxy` (iptables DNAT). Its /proc path is never a native app-folder —
            // published-port truth belongs to the Docker branch (docker inspect), so the
            // listening sweep positively recognises and skips it rather than mapping it.
            if (isDockerProxy(listener.process())) {
                continue;
            }
            listeningPids.add(listener.pid());
            String cmdline = cmdline(session, listener.pid());
            Runtime runtime = runtimeOf(session, listener.pid());
            String container = runtime == Runtime.DOCKER ? containerName(session, listener.pid()) : null;
            Family family = classify(listener.process(), cmdline);
            // A java listener is only a springboot monitor if Actuator actually answers;
            // otherwise its /actuator/* probes would all be dead. Fall back to an HTTP
            // liveness monitor (GET / + process) — the actuator-less Spring Boot case.
            if (family == Family.SPRINGBOOT && !respondsToActuator(session, listener.port())) {
                family = Family.HTTP;
            }
            String appName = appName(family, listener, cmdline, container, session);
            // cgroup-before-cwd guard (spec-055 / 054): a DOCKER PID's /proc/<pid>/cwd is an
            // overlayfs path, not a host context — never map it. Docker contexts come from
            // `docker inspect` (056). Only PROCESS/SYSTEMD runtimes feed ContextMapper.
            ContextMapper.Context context = runtime == Runtime.DOCKER
                    ? null : resolveContext(session, listener.pid());
            // Common-service fingerprinting (Decision 5): a native nginx/postgres/mysql/mariadb
            // listener is identified against the fixed ServiceCatalog. Its context becomes the
            // env-verified service data dir (PGDATA/MYSQL_DATADIR beats the catalog default), and
            // its confidence reflects signal agreement — process AND the catalog port ⇒ high.
            String confidence = null;
            ServiceCatalog.Service service = runtime == Runtime.DOCKER ? null
                    : ServiceCatalog.fingerprintByProcess(listener.process(), cmdline);
            if (service != null) {
                context = resolveContextForDir(session,
                        verifiedDataDir(session, listener.pid(), service));
                confidence = service.defaultPort() == listener.port() ? "high" : "low";
            }
            String sourceNote = listeningSourceNote(runtime, listener.port());
            resolved.add(new Resolved(family, appName, listener.port(), runtime.label, context,
                    sourceNote, confidence));
            if (family == Family.FASTAPI && respondsToMetrics(session, listener.port())) {
                prometheus = true;
            }
        }

        // Non-listening sweep (spec-056 Decision 3 / 054 D4): union the workers, systemd
        // services, cron jobs and interpreter processes that own no listening socket, so the
        // "structurally undetectable" population becomes discoverable. Each emits the same
        // record shape with a sentinel port 0 and its own sourceNote, de-duplicated by PID
        // against the listening set.
        resolved.addAll(nonListeningApps(session, listeningPids));

        if (resolved.isEmpty()) {
            return List.of();
        }

        // Sibling enumeration (spec-055 D4): the app-scripts that resolve to one context,
        // grouped under its identity key. Distinct, in first-seen order.
        Map<String, List<String>> siblingsByContext = new LinkedHashMap<>();
        for (Resolved r : resolved) {
            if (r.context() != null) {
                siblingsByContext.computeIfAbsent(r.context().key(), k -> new ArrayList<>());
                List<String> group = siblingsByContext.get(r.context().key());
                if (!group.contains(r.appName())) {
                    group.add(r.appName());
                }
            }
        }

        // Route each mapped app into its family's pre-fill list. LinkedHashMap so
        // the springboot/fastapi/generic recipes propose in a stable order.
        Map<Family, List<AppPortItem>> byFamily = new LinkedHashMap<>();
        for (Resolved r : resolved) {
            ContextMapper.Context ctx = r.context();
            AppPortItem item = new AppPortItem(r.appName(), r.port(), r.runtime(),
                    ctx == null ? null : ctx.scriptFolder(),
                    ctx == null ? null : ctx.key(),
                    ctx == null ? null : ctx.display(),
                    ctx == null ? List.of() : siblingsByContext.getOrDefault(ctx.key(), List.of()),
                    r.sourceNote(), r.confidence());
            byFamily.computeIfAbsent(r.family(), f -> new ArrayList<>()).add(item);
        }

        List<ProposedRecipe> proposals = new ArrayList<>();
        for (Map.Entry<Family, List<AppPortItem>> entry : byFamily.entrySet()) {
            proposals.add(recipeFor(entry.getKey(), entry.getValue(), prometheus));
        }
        return proposals;
    }

    // --- probes -------------------------------------------------------------

    /** Listening TCP sockets owned by the login user, via {@code ss} (netstat fallback). */
    private List<Listener> listeners(SshSession session) {
        List<String> ss = Probes.lines(session, List.of("ss", "-ltnp"));
        if (!ss.isEmpty()) {
            return parseSs(ss);
        }
        return parseNetstat(Probes.lines(session, List.of("netstat", "-ltnp")));
    }

    /** The whitespace-normalised {@code /proc/<pid>/cmdline} (NUL-separated on disk). */
    private String cmdline(SshSession session, String pid) {
        List<String> lines = Probes.lines(session, List.of("cat", "/proc/" + pid + "/cmdline"));
        return String.join(" ", lines).replace('\0', ' ').trim();
    }

    /** {@code docker} if the PID's cgroup resolves to a container, else systemd/process. */
    private Runtime runtimeOf(SshSession session, String pid) {
        for (String line : Probes.lines(session, List.of("cat", "/proc/" + pid + "/cgroup"))) {
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
    private String containerName(SshSession session, String pid) {
        for (String line : Probes.lines(session, List.of("cat", "/proc/" + pid + "/cgroup"))) {
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
    private boolean respondsToActuator(SshSession session, int port) {
        // Same fixed read-only GET shape as the metrics probe; -f makes a 404/redirect
        // fail so an actuator-less app yields no lines.
        return !Probes.lines(session,
                List.of("curl", "-sf", "-m", "2", "http://127.0.0.1:" + port + "/actuator/health")).isEmpty();
    }

    /** Whether the FastAPI app exposes a Prometheus {@code /metrics} endpoint (HTTP 2xx). */
    private boolean respondsToMetrics(SshSession session, int port) {
        // Concrete integer port (from ss) built into a fixed read-only GET; -f makes a
        // 404 fail so a non-Prometheus app yields no lines.
        return !Probes.lines(session,
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
                           SshSession session) {
        if (container != null) {
            return sanitize(container, listener.port());
        }
        String derived = switch (family) {
            case SPRINGBOOT, HTTP -> springBootName(cmdline, session, listener.pid());
            case FASTAPI -> fastApiName(cmdline);
            case GENERIC -> listener.process();
        };
        return sanitize(derived, listener.port());
    }

    /** Generic jar/dir names too vague to label an app — trigger a deeper probe instead. */
    private static final java.util.Set<String> GENERIC_NAMES = java.util.Set.of(
            "app", "application", "web", "api", "server", "service", "main", "demo",
            "start", "run", "boot", "target", "build", "dist");

    private String springBootName(String cmdline, SshSession session, String pid) {
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
        String cwd = deployDirName(session, pid);
        if (cwd != null && !isGeneric(cwd)) {
            return cwd;
        }
        String fromManifest = manifestAppName(session, jarPath);
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

    /** Basename of {@code /proc/<pid>/cwd} (the deploy dir), or null. Name-derivation still
     * reads the basename (spec-055 D5: basename is the identity seed); the full logical path
     * feeds {@link ContextMapper}. */
    private String deployDirName(SshSession session, String pid) {
        String path = cwdPath(session, pid);
        if (path == null) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        return base.isEmpty() ? null : base;
    }

    // --- context mapping (spec-055) -----------------------------------------

    /**
     * Maps a PROCESS/SYSTEMD listener to its owning context via {@link ContextMapper}, using
     * the app-script's <em>full logical</em> {@code cwd} and its resolved <em>physical</em>
     * path. Both reads are fixed-argv, read-only, no-sudo ({@code readlink}); the marker-file
     * probe for the second wrapper hop is supplied lazily so it only runs when a hop is
     * actually being weighed. Returns {@code null} when the {@code cwd} is unreadable.
     */
    private ContextMapper.Context resolveContext(SshSession session, String pid) {
        String logical = cwdPath(session, pid);
        if (logical == null) {
            return null;
        }
        String physical = realCwdPath(session, pid);
        return ContextMapper.resolveContext(logical, physical, dir -> hasMarkerFile(session, dir));
    }

    /** The full logical {@code readlink /proc/<pid>/cwd}, or null. */
    private String cwdPath(SshSession session, String pid) {
        List<String> out = Probes.lines(session, List.of("readlink", "/proc/" + pid + "/cwd"));
        if (out.isEmpty()) {
            return null;
        }
        String path = out.get(0).trim();
        return path.isEmpty() ? null : path;
    }

    /** The resolved physical {@code readlink -f /proc/<pid>/cwd} (dedup key, D1), or null. */
    private String realCwdPath(SshSession session, String pid) {
        List<String> out = Probes.lines(session, List.of("readlink", "-f", "/proc/" + pid + "/cwd"));
        if (out.isEmpty()) {
            return null;
        }
        String path = out.get(0).trim();
        return path.isEmpty() ? null : path;
    }

    /** Whether {@code dir} carries any {@link ContextMapper#markerFiles() marker file} ({@code ls -a}). */
    private boolean hasMarkerFile(SshSession session, String dir) {
        List<String> entries = Probes.lines(session, List.of("ls", "-a", dir));
        return entries.stream().anyMatch(ContextMapper.markerFiles()::contains);
    }

    // --- non-listening sweep (spec-056 Decision 3) --------------------------

    /**
     * The union of the three non-listening enumerations — running systemd services, cron
     * jobs, and interpreter processes with no listening socket — each resolved to its owning
     * context and de-duplicated by PID against {@code seenPids} (the listening set) and each
     * other. A PID whose cgroup routes it to Docker (Decision 2) is dropped; its context comes
     * from the Docker branch, never from its overlayfs {@code /proc} path.
     *
     * <p><strong>S4 safety.</strong> Every read is a constant argv or a fixed {@code sh -c}
     * script; the only bound inputs are a validated PID (integer) or a service-unit name matched
     * against {@link #SERVICE_UNIT}. No free-form param, no mutating verb, no {@code sudo}.
     */
    private List<Resolved> nonListeningApps(SshSession session, Set<String> seenPids) {
        List<Resolved> out = new ArrayList<>();
        Set<String> emitted = new HashSet<>(seenPids);
        out.addAll(systemdApps(session, emitted));
        out.addAll(interpreterApps(session, emitted));
        out.addAll(cronApps(session));
        return out;
    }

    /** Running {@code *.service} units (spec-056): unit → {@code MainPID} → context, runtime systemd. */
    private List<Resolved> systemdApps(SshSession session, Set<String> emitted) {
        List<Resolved> out = new ArrayList<>();
        List<String> units = Probes.lines(session, List.of(
                "systemctl", "list-units", "--type=service", "--state=running", "--no-legend", "--plain"));
        for (String line : units) {
            String unit = line.trim().split("\\s+")[0];
            if (!SERVICE_UNIT.matcher(unit).matches()) {
                continue;
            }
            String pid = mainPid(session, unit);
            if (pid == null || !emitted.add(pid)) {
                continue; // no main process (oneshot), or already found via a socket / another sweep
            }
            if (runtimeOf(session, pid) == Runtime.DOCKER) {
                continue; // Decision 2: a containerised unit's /proc path is never a host context
            }
            String appName = sanitize(unit.substring(0, unit.length() - ".service".length()), 0);
            out.add(new Resolved(Family.GENERIC, appName, 0, Runtime.SYSTEMD.label,
                    resolveContext(session, pid), "declared app · systemd unit · no port", null));
        }
        return out;
    }

    /** The {@code MainPID} of a running unit ({@code systemctl show}), or null when it has none. */
    private String mainPid(SshSession session, String unit) {
        List<String> out = Probes.lines(session,
                List.of("systemctl", "show", "-p", "MainPID", "--value", unit));
        if (out.isEmpty()) {
            return null;
        }
        String pid = out.get(0).trim();
        return pid.isEmpty() || pid.equals("0") ? null : pid;
    }

    /**
     * Interpreter processes (spec-056): {@code ps -eo pid=,args=} → an argv whose leading token
     * is a known interpreter followed by a script file argument. The script names the app; its
     * physical context comes from {@code /proc/<pid>/cwd}, like the listening branch. Skips any
     * PID already emitted by an earlier sweep or routed to Docker (Decision 2).
     */
    private List<Resolved> interpreterApps(SshSession session, Set<String> emitted) {
        List<Resolved> out = new ArrayList<>();
        for (String line : Probes.lines(session, List.of("ps", "-eo", "pid=,args="))) {
            String trimmed = line.trim();
            int sp = trimmed.indexOf(' ');
            if (sp <= 0) {
                continue;
            }
            String pid = trimmed.substring(0, sp);
            if (!pid.matches("\\d+")) {
                continue;
            }
            String script = interpreterScript(trimmed.substring(sp + 1).trim());
            if (script == null || !emitted.add(pid)) {
                continue;
            }
            if (runtimeOf(session, pid) == Runtime.DOCKER) {
                continue; // Decision 2
            }
            String appName = sanitize(baseName(script), 0);
            out.add(new Resolved(Family.GENERIC, appName, 0, Runtime.PROCESS.label,
                    resolveContext(session, pid), "declared app · interpreter process · no port", null));
        }
        return out;
    }

    /**
     * The interpreter's target script from a {@code ps args} string, or null when the argv is
     * not {@code <interpreter> [flags] <script-file>}. A leading interpreter is required and the
     * first non-flag token must look like a script (absolute path or a known script extension),
     * so {@code python3 -m uvicorn} (a module, not a file) is correctly rejected.
     */
    private String interpreterScript(String args) {
        String[] tokens = args.split("\\s+");
        if (tokens.length < 2 || !INTERPRETERS.contains(baseName(tokens[0]))) {
            return null;
        }
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.startsWith("-")) {
                continue; // an interpreter flag (-u, -O, -jar, …) precedes the script
            }
            return t.startsWith("/") || SCRIPT_ARG.matcher(t).matches() ? t : null;
        }
        return null;
    }

    /**
     * Cron-launched apps (spec-056): read every cron source ({@link #CRON_PROBE_SCRIPT}) and take
     * each command's leading absolute script path. A cron app has no live PID, so it is keyed on
     * its script path and mapped through the script's directory. Runtime process, sentinel port.
     */
    private List<Resolved> cronApps(SshSession session) {
        List<Resolved> out = new ArrayList<>();
        Set<String> seenScripts = new HashSet<>();
        List<String> lines = Probes.lines(session, List.of("sh", "-c", CRON_PROBE_SCRIPT));
        for (String path : cronScriptPaths(lines)) {
            if (!seenScripts.add(path)) {
                continue;
            }
            ContextMapper.Context context = resolveContextForDir(session, parentDir(path));
            out.add(new Resolved(Family.GENERIC, sanitize(baseName(path), 0), 0, Runtime.PROCESS.label,
                    context, "declared app · cron-launched · no port", null));
        }
        return out;
    }

    /** The leading absolute path of each non-comment cron line (the command's script), in order. */
    private List<String> cronScriptPaths(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            for (String token : trimmed.split("\\s+")) {
                if (token.startsWith("/")) {
                    out.add(token);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * The verified data dir of a fingerprinted service (spec-056 Decision 5 "verify" step): a
     * {@code PGDATA}/{@code MYSQL_DATADIR} value on the process's environment overrides the
     * catalog default; nginx (no env var) keeps the default. The read is unprivileged and may
     * be denied for another user's process — then the catalog default stands.
     */
    private String verifiedDataDir(SshSession session, String pid,
                                   ServiceCatalog.Service service) {
        if (service.dataDirEnvVar() != null) {
            String override = envValue(session, pid, service.dataDirEnvVar());
            if (override != null) {
                return override;
            }
        }
        return service.dataDir();
    }

    /** A process environment variable's value from {@code /proc/<pid>/environ} (NUL-separated), or null. */
    private String envValue(SshSession session, String pid, String var) {
        for (String line : Probes.lines(session, List.of("cat", "/proc/" + pid + "/environ"))) {
            for (String entry : line.split("\0")) {
                if (entry.startsWith(var + "=")) {
                    String value = entry.substring(var.length() + 1).trim();
                    return value.isEmpty() ? null : value;
                }
            }
        }
        return null;
    }

    /** Maps a script's <em>directory</em> to its context (cron has no PID to read a cwd from). */
    private ContextMapper.Context resolveContextForDir(SshSession session, String dir) {
        if (dir == null) {
            return null;
        }
        return ContextMapper.resolveContext(dir, realPath(session, dir),
                d -> hasMarkerFile(session, d));
    }

    /** {@code readlink -f <path>} (the physical dedup path), or null when unreadable. */
    private String realPath(SshSession session, String path) {
        List<String> out = Probes.lines(session, List.of("readlink", "-f", path));
        if (out.isEmpty()) {
            return null;
        }
        String p = out.get(0).trim();
        return p.isEmpty() ? null : p;
    }

    /** The last path segment of {@code path} (its basename). */
    private String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** The parent directory of {@code path}, or {@code /} for a top-level file. */
    private String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "/";
    }

    /**
     * The app name from the jar's {@code META-INF/MANIFEST.MF} {@code Start-Class} (a
     * Spring Boot fat jar's real main class; its {@code Main-Class} is only the boot
     * loader), falling back to a non-loader {@code Main-Class} for a plain jar. Package
     * is dropped, a trailing {@code Application}/{@code Kt} stripped, camelCase → kebab:
     * {@code com.ex.BirthdayRsvpApplication} → {@code birthday-rsvp}. Null when {@code
     * unzip} is absent or nothing usable is found.
     */
    private String manifestAppName(SshSession session, String jarPath) {
        // Only a plain jar path (no shell metacharacters) is passed, as an argv element.
        if (jarPath == null || !jarPath.matches("[A-Za-z0-9._/-]+\\.jar")) {
            return null;
        }
        List<String> raw = Probes.lines(session,
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
                    cpuProbe());
            case FASTAPI -> fastApiActions(prometheus);
            case HTTP -> List.of(
                    endpointProbe("liveness", "HTTP liveness (GET / — no Actuator present).", "/"),
                    processProbe("process", "RSS/CPU/threads/fds from /proc (process-probe supplement)."),
                    cpuProbe());
            case GENERIC -> List.of(
                    processProbe("process", "RSS/CPU/threads/fds from /proc (process-probe only)."),
                    cpuProbe());
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
     * The provenance string for a listening app (spec-056): which sweep branch found it.
     * A container-runtime listener (a host socket the login user can still see) is a
     * container; everything else is a host app folder. Names the port, never a path (S9).
     */
    private String listeningSourceNote(Runtime runtime, int port) {
        String branch = runtime == Runtime.DOCKER ? "container" : "app folder";
        return branch + " · discovered via port :" + port;
    }

    /**
     * Whether a listener's owning process is docker's userland port forwarder (Decision 4).
     * {@code ss} reports the DNAT host process as {@code docker-proxy}; that port is a
     * container's published port and its truth comes from the Docker branch, never here.
     */
    private boolean isDockerProxy(String process) {
        return "docker-proxy".equals(process);
    }

    /** One listening socket: its port, owning PID, and process name (login user only). */
    private record Listener(int port, String pid, String process) {
    }

    /**
     * A classified app with its resolved {@link ContextMapper.Context} (spec-055), its
     * {@code sourceNote} provenance and its fingerprint {@code confidence} (spec-056);
     * {@code context} is {@code null} for a docker-overlayfs app (mapped by 056's docker
     * branch instead) and {@code confidence} is {@code null} unless it fingerprinted to a
     * common service.
     */
    private record Resolved(Family family, String appName, int port, String runtime,
                            ContextMapper.Context context, String sourceNote, String confidence) {
    }
}
