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
import java.util.HashMap;
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
 * <p>Every app-monitor family also carries the four <strong>footprint</strong> axes
 * (spec-057): a PSS {@code ram} probe ({@link #PSS_RAM_PROBE_SCRIPT}), a CPU-<em>rate</em>
 * {@code cpu} probe ({@link #CPU_RATE_PROBE_SCRIPT}, replacing spec-032's lifetime-average
 * {@code ps %cpu}), a per-context {@code disk} probe ({@link #DISK_PROBE_SCRIPT}), and the
 * on-demand {@code sudo} re-probe variants that upgrade a permission-denied reading. Each is a
 * bounded, read-only {@link RecipeType#MONITOR} action, gated like any other.
 *
 * <p>spec-025; the app-level CPU check added in spec-032; the footprint axes in spec-057.
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
     * The fixed process-tree <strong>CPU-rate</strong> probe (spec-057, replacing the
     * lifetime-average {@code ps %cpu} of spec-032): from the listener port {@code $1} resolve
     * the owning PID(s) via {@code ss}, expand each to its process tree (its PID plus direct
     * children, covering gunicorn/uvicorn workers and Spring Boot helpers), then sample
     * {@code utime+stime} (fields 14+15 of {@code /proc/<pid>/stat}) <em>twice inside one
     * exec</em>, {@code sleep 4} apart, stamping {@code date +%s.%N} on both. Field 22
     * ({@code starttime}) is emitted so the client can guard against PID churn between the two
     * samples. The client divides Δticks by {@code CLK_TCK} and the <em>measured</em> Δt to get
     * a cross-core {@code %cpu} (Σ per-process core-fractions × 100; may exceed 100), matching
     * {@code denom.cores}. Read-only, login-user, no {@code sudo}; the port is the sole bound
     * positional argument, never interpolated at authoring time (S4). This measures what the
     * app is doing <em>now</em>, unlike the lifetime average that is meaningless for a worker
     * that burned CPU once then idled.
     */
    private static final String CPU_RATE_PROBE_SCRIPT = String.join("\n",
            "port=\"$1\"",
            "pids=$(ss -ltnpH 2>/dev/null | grep -E \":$port \" | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)",
            "[ -z \"$pids\" ] && { echo \"no listener on port $port\"; exit 0; }",
            "tree=\"\"",
            "for pid in $pids; do",
            "  tree=\"$tree $pid $(ps -o pid= --ppid \"$pid\" 2>/dev/null | tr '\\n' ' ')\"",
            "done",
            "echo \"clk_tck=$(getconf CLK_TCK 2>/dev/null || echo 100)\"",
            // Read fields 14 (utime), 15 (stime) and 22 (starttime) after the last ')', so a
            // comm containing spaces/parens never shifts the columns.
            "sample() {",
            "  for p in $tree; do",
            "    awk -v p=\"$p\" '{i=index($0,\") \"); r=substr($0,i+2); n=split(r,b,\" \");"
                    + " print \"pid=\" p \" ticks=\" (b[12]+b[13]) \" starttime=\" b[20]}'"
                    + " \"/proc/$p/stat\" 2>/dev/null",
            "  done",
            "}",
            "echo \"t0=$(date +%s.%N)\"",
            "echo \"## s0\"; sample",
            "sleep 4",
            "echo \"t1=$(date +%s.%N)\"",
            "echo \"## s1\"; sample");

    /**
     * The fixed <strong>PSS RAM</strong> probe (spec-057): from the listener port {@code $1}
     * resolve the owning PID(s) via {@code ss}, then for each PID sum {@code Pss:} from
     * {@code /proc/<pid>/smaps_rollup} — the instantaneous proportional set size, the honest
     * RAM figure. It is emitted per PID so the client sums to the context. <strong>Never a
     * sum of RSS:</strong> summing {@code VmRSS} across N workers sharing libraries overstates
     * by up to (N−1)×shared. When {@code smaps_rollup} is denied (an unprivileged read of
     * another user's process), it degrades to {@code VmRSS} from {@code /proc/<pid>/status} and
     * stamps {@code ram_confidence=low reason=procfs-denied}, so the fallback is presented as a
     * labelled ≤ upper bound, never as the same metric. Read-only, login-user, no {@code sudo}
     * (the on-demand sudo re-probe upgrades a degraded reading); the port is the sole bound
     * positional argument (S4).
     */
    private static final String PSS_RAM_PROBE_SCRIPT = String.join("\n",
            "port=\"$1\"",
            "pids=$(ss -ltnpH 2>/dev/null | grep -E \":$port \" | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)",
            "[ -z \"$pids\" ] && { echo \"no listener on port $port\"; exit 0; }",
            "for pid in $pids; do",
            "  echo \"## pid $pid\"",
            "  v=$(awk '/^Pss:/{s+=$2} END{if(s>0)print s}' \"/proc/$pid/smaps_rollup\" 2>/dev/null)",
            "  if [ -n \"$v\" ]; then",
            "    echo \"Pss: $v kB\"",
            "  else",
            "    grep -E '^VmRSS:' \"/proc/$pid/status\" 2>/dev/null",
            "    echo \"ram_confidence=low reason=procfs-denied\"",
            "  fi",
            "done");

    /**
     * The fixed per-context <strong>disk</strong> probe (spec-057): {@code du -sbx} on the
     * resolved app-folder ({@code $1}, bound server-side from the S9-secret contextKey, never
     * caller-supplied). {@code -x} keeps the walk on the app-folder's own filesystem so an
     * under-mount on another device is not crossed; {@code findmnt} cross-checks and adds an
     * explicit {@code --exclude} per under-mount (belt-and-suspenders against a bind-mount that
     * loops back onto the same fs — the <strong>double-counting rule</strong>). The numerator
     * is therefore <strong>bytes on the root/data-root filesystem</strong> — the exact fs
     * {@code parseDfTotal} anchors {@code denom.diskBytes} to, so it subtracts cleanly from
     * OTHER. Bounded with {@code timeout}/{@code nice}/{@code ionice} so it never hammers the
     * host; on timeout it degrades to a per-child {@code --max-depth=1} sum (a labelled lower
     * bound) rather than failing. Read-only, login-user, no {@code sudo}.
     */
    private static final String DISK_PROBE_SCRIPT = String.join("\n",
            "dir=\"$1\"",
            "[ -d \"$dir\" ] || { echo \"no dir $dir\"; exit 0; }",
            "echo \"app_folder=$dir\"",
            "excl=\"\"",
            "for m in $(findmnt -rno TARGET 2>/dev/null | grep \"^$dir/\" | sort -u); do",
            "  excl=\"$excl --exclude=$m\"",
            "done",
            "if out=$(timeout 120 nice -n19 ionice -c3 du -sbx $excl \"$dir\" 2>/dev/null) && [ -n \"$out\" ]; then",
            "  echo \"$out\" | awk '{print \"du_bytes=\" $1}'",
            "else",
            "  echo \"disk_confidence=low reason=du-timeout\"",
            "  timeout 120 nice -n19 ionice -c3 du -sbx --max-depth=1 $excl \"$dir\" 2>/dev/null"
                    + " | awk '{s+=$1} END{if(s>0)print \"du_bytes=\" s}'",
            "fi");
    /**
     * The fixed {@code /proc/net/tcp{,6}} port-recovery fallback (spec-062 Decision 1): when a
     * LISTEN socket's owner is unreadable (an {@code ss}/{@code netstat} blank process column, or
     * neither tool present), read the LISTEN rows ({@code st == 0A}) of {@code /proc/net/tcp} +
     * {@code /proc/net/tcp6} — printing each socket's hex {@code local_address} ({@code $2}) and
     * {@code inode} ({@code $10}) — then scan every {@code /proc/<pid>/fd} for {@code socket:[<inode>]}
     * targets so Java can join {@code inode → PID}. The body is a source-controlled constant with
     * <strong>zero bound inputs</strong>, run no-sudo (S4/S9-safe); unreadable dirs vanish into
     * {@code 2>/dev/null} — exactly the foreign-PID case that cannot be attributed.
     */
    private static final String PORT_FALLBACK_SCRIPT = String.join("\n",
            "awk '$4==\"0A\" {print \"L\", $2, $10}' /proc/net/tcp /proc/net/tcp6 2>/dev/null",
            "ls -l /proc/[0-9]*/fd 2>/dev/null | awk '/^\\/proc\\// {sub(/:$/,\"\"); split($0,a,\"/\"); pid=a[3]}"
                    + " /socket:\\[/ {print \"F\", pid, $NF}'");

    /** The fixed packaged-binary prefixes an {@code exe} target under is <em>not</em> a deploy folder (spec-062 D3). */
    private static final Set<String> PACKAGED_BINARY_ROOTS = Set.of(
            "/usr", "/bin", "/sbin", "/lib", "/lib64", "/snap");

    /** An nginx {@code root <path>;} directive (surrounding quotes stripped by the capture group). */
    private static final Pattern NGINX_ROOT = Pattern.compile("^\\s*root\\s+\"?([^;\"\\s]+)\"?\\s*;");

    /** nginx stock default-server document roots to discard when picking the modal real root (spec-062 D4). */
    private static final Set<String> NGINX_DEFAULT_ROOTS = Set.of("/usr/share/nginx/html", "/var/www/html");

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
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        SshTarget target = Probes.target(machine);
        List<Listener> listeners = listeners(ssh, target);

        // Classify each app and map it to its owning context (spec-055). Two passes: first
        // resolve every listener, then group by contextKey so a context can enumerate the
        // sibling app-scripts collapsing to it (grouping metadata only, spec-055 D4).
        List<Resolved> resolved = new ArrayList<>();
        Set<String> listeningPids = new HashSet<>();
        Set<String> claimedKeys = new HashSet<>();
        List<Listener> unattributed = new ArrayList<>();
        boolean prometheus = false;
        for (Listener listener : listeners) {
            // spec-062 Decision 1: a genuinely unattributed listener (no owner the login user
            // could read) carries no PID, so it can drive no /proc read. Defer it until every
            // channel has claimed its ports, then reconcile (below) — never read /proc/<null>.
            if (!listener.attributed()) {
                unattributed.add(listener);
                continue;
            }
            // Decision 4: a published container port may be owned on the host by
            // `docker-proxy` (iptables DNAT). Its /proc path is never a native app-folder —
            // published-port truth belongs to the Docker branch (docker inspect), so the
            // listening sweep positively recognises and skips it rather than mapping it.
            if (isDockerProxy(listener.process())) {
                continue;
            }
            listeningPids.add(listener.pid());
            claimedKeys.add(listener.key());
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
            // cgroup-before-cwd guard (spec-055 / 054): a DOCKER PID's /proc/<pid>/cwd is an
            // overlayfs path, not a host context — never map it. Docker contexts come from
            // `docker inspect` (056). Only PROCESS/SYSTEMD runtimes feed ContextMapper.
            ContextMapper.Context context = runtime == Runtime.DOCKER
                    ? null : resolveContext(ssh, target, listener.pid());
            // Common-service fingerprinting (Decision 5): a native nginx/postgres/mysql/mariadb
            // listener is identified against the fixed ServiceCatalog. Its context becomes the
            // env-verified service data dir (PGDATA/MYSQL_DATADIR beats the catalog default), and
            // its confidence reflects signal agreement — process AND the catalog port ⇒ high.
            String confidence = null;
            ServiceCatalog.Service service = runtime == Runtime.DOCKER ? null
                    : ServiceCatalog.fingerprintByProcess(listener.process(), cmdline);
            if (service != null) {
                context = resolveContextForDir(ssh, target,
                        verifiedDataDir(ssh, target, listener.pid(), service));
                confidence = service.defaultPort() == listener.port() ? "high" : "low";
            }
            String sourceNote = listeningSourceNote(runtime, listener.port());
            resolved.add(new Resolved(family, appName, listener.port(), runtime.label, context,
                    sourceNote, confidence));
            if (family == Family.FASTAPI && respondsToMetrics(ssh, target, listener.port())) {
                prometheus = true;
            }
        }

        // Non-listening sweep (spec-056 Decision 3 / 054 D4): union the workers, systemd
        // services, cron jobs and interpreter processes that own no listening socket, so the
        // "structurally undetectable" population becomes discoverable. Each emits the same
        // record shape with a sentinel port 0 and its own sourceNote, de-duplicated by PID
        // against the listening set.
        resolved.addAll(nonListeningApps(ssh, target, listeningPids));

        // spec-062 Decision 1 reconciliation: a listener no channel could attribute becomes a
        // single degraded `app-<port>` record — null owner, GENERIC, runtime process, confidence
        // low, a path-free sourceNote — but only when no attributed/fingerprinted record already
        // owns its (addr, port) (a systemd/fingerprint-owned port is never doubled as an app card),
        // and de-duplicated by (addr, port) so 127.0.0.1:8080 and 0.0.0.0:8080 both survive.
        Set<String> emittedUnattributed = new HashSet<>();
        for (Listener u : unattributed) {
            if (claimedKeys.contains(u.key()) || !emittedUnattributed.add(u.key())) {
                continue;
            }
            resolved.add(new Resolved(Family.GENERIC, sanitize(null, u.port()), u.port(),
                    Runtime.PROCESS.label, null,
                    "unattributed listener · discovered via port :" + u.port() + " · owner unreadable",
                    "low"));
        }

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

    /**
     * The listening TCP inventory (spec-062 Decision 1): {@code ss -ltnp} (or {@code netstat}
     * fallback) parsed into {@link Listener}s — attributed where the tool named the owner,
     * <strong>unattributed</strong> (null pid) where it left the process column blank. Whenever
     * any unattributed listener survives — or neither tool produced output — the constant
     * {@code /proc/net/tcp{,6}} + fd-inode fallback runs <em>once</em> and is merged in by
     * {@code (addr, port)}: an {@code ss}-attributed listener always wins, an unattributed one is
     * filled with a joined PID, and a port no tool saw is added. The inventory is therefore never
     * silently short a listening port.
     */
    private List<Listener> listeners(SshExecutor ssh, SshTarget target) {
        List<String> ss = Probes.lines(ssh, target, List.of("ss", "-ltnp"));
        List<Listener> base = !ss.isEmpty() ? parseSs(ss)
                : parseNetstat(Probes.lines(ssh, target, List.of("netstat", "-ltnp")));
        boolean anyUnattributed = base.stream().anyMatch(l -> !l.attributed());
        if (!base.isEmpty() && !anyUnattributed) {
            return base;
        }
        return mergeFallback(base, fallbackListeners(ssh, target));
    }

    /**
     * Merges the {@code /proc/net/tcp{,6}} fallback into the {@code ss}/{@code netstat} base,
     * keyed on {@code (addr, port)} (spec-062 Decision 1): an already-attributed base listener
     * wins over any fallback attribution for the same key; an unattributed base listener is
     * upgraded when the fallback join recovered its PID; a key the base never held is added
     * (a no-{@code ss} host, or a foreign socket whose PID stayed unrecoverable — a null-PID port
     * that still survives). {@code LinkedHashMap} keeps a stable, first-seen order.
     */
    private List<Listener> mergeFallback(List<Listener> base, List<Listener> fallback) {
        Map<String, Listener> byKey = new LinkedHashMap<>();
        for (Listener l : base) {
            byKey.putIfAbsent(l.key(), l);
        }
        for (Listener f : fallback) {
            Listener existing = byKey.get(f.key());
            if (existing == null) {
                byKey.put(f.key(), f);
            } else if (!existing.attributed() && f.attributed()) {
                byKey.put(f.key(), new Listener(existing.addr(), existing.port(), f.pid(), f.process()));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * The fallback listeners recovered from {@link #PORT_FALLBACK_SCRIPT}: each {@code L} row is a
     * LISTEN socket ({@code hexAddr:hexPort inode}) decoded to an {@code (addr, port)}; each
     * {@code F} row joins a {@code socket:[inode]} to a PID. A socket held by several preforked
     * PIDs (a shared listening inode) resolves to the <strong>lowest</strong> PID — the master,
     * which drives context derivation. A row whose inode never joins a PID stays an unattributed
     * port (null PID), never dropped.
     */
    private List<Listener> fallbackListeners(SshExecutor ssh, SshTarget target) {
        List<String> lines = Probes.lines(ssh, target, List.of("sh", "-c", PORT_FALLBACK_SCRIPT));
        Map<String, String[]> inodeToEndpoint = new LinkedHashMap<>();
        Map<String, String> inodeToPid = new HashMap<>();
        for (String line : lines) {
            String[] t = line.trim().split("\\s+");
            if (t.length != 3) {
                continue;
            }
            if (t[0].equals("L")) {
                String[] endpoint = decodeHexEndpoint(t[1]);
                if (endpoint != null) {
                    inodeToEndpoint.putIfAbsent(t[2], endpoint);
                }
            } else if (t[0].equals("F")) {
                String inode = extractInode(t[2]);
                if (inode != null && t[1].matches("\\d+")) {
                    String current = inodeToPid.get(inode);
                    if (current == null || Long.parseLong(t[1]) < Long.parseLong(current)) {
                        inodeToPid.put(inode, t[1]);
                    }
                }
            }
        }
        List<Listener> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : inodeToEndpoint.entrySet()) {
            String[] endpoint = e.getValue();
            out.add(new Listener(endpoint[0], Integer.parseInt(endpoint[1]), inodeToPid.get(e.getKey()), null));
        }
        return out;
    }

    /** The inode from a {@code socket:[12345]} fd target, or null when it is not a socket link. */
    private String extractInode(String target) {
        if (target.startsWith("socket:[") && target.endsWith("]")) {
            return target.substring("socket:[".length(), target.length() - 1);
        }
        return null;
    }

    /**
     * Decodes a {@code /proc/net/tcp{,6}} {@code local_address} token ({@code hexAddr:hexPort})
     * into {@code {addr, port}} (spec-062 Decision 1). The hex address is little-endian per
     * 32-bit word: an 8-hex IPv4 or a 32-hex IPv6; the port is a plain base-16 integer. Null when
     * the token is malformed or the port is not positive.
     */
    private String[] decodeHexEndpoint(String token) {
        int colon = token.lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        String hexAddr = token.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(token.substring(colon + 1), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0) {
            return null;
        }
        String addr = hexAddr.length() == 8 ? decodeHexIpv4(hexAddr)
                : hexAddr.length() == 32 ? decodeHexIpv6(hexAddr) : null;
        return addr == null ? null : new String[]{addr, Integer.toString(port)};
    }

    /** {@code 0100007F} (little-endian) → {@code 127.0.0.1}. */
    private String decodeHexIpv4(String hex) {
        try {
            int b0 = Integer.parseInt(hex.substring(0, 2), 16);
            int b1 = Integer.parseInt(hex.substring(2, 4), 16);
            int b2 = Integer.parseInt(hex.substring(4, 6), 16);
            int b3 = Integer.parseInt(hex.substring(6, 8), 16);
            return b3 + "." + b2 + "." + b1 + "." + b0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A 32-hex {@code /proc/net/tcp6} address → its canonical {@code ::}-compressed form (each
     * 32-bit word is byte-reversed before formatting). All-zero → {@code ::} (the {@code [::]}
     * any-interface bind), matching the {@code ss} {@code [::]} canonicalisation. Null on a parse
     * error.
     */
    private String decodeHexIpv6(String hex) {
        int[] groups = new int[8];
        try {
            byte[] b = new byte[16];
            for (int w = 0; w < 4; w++) {
                for (int i = 0; i < 4; i++) {
                    int src = w * 8 + (3 - i) * 2;
                    b[w * 4 + i] = (byte) Integer.parseInt(hex.substring(src, src + 2), 16);
                }
            }
            for (int g = 0; g < 8; g++) {
                groups[g] = ((b[g * 2] & 0xff) << 8) | (b[g * 2 + 1] & 0xff);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return compressIpv6(groups);
    }

    /** Formats 8 16-bit groups as a lowercase IPv6 string, compressing the longest zero run to {@code ::}. */
    private String compressIpv6(int[] groups) {
        int bestStart = -1;
        int bestLen = 0;
        int runStart = -1;
        int runLen = 0;
        for (int i = 0; i < 8; i++) {
            if (groups[i] == 0) {
                if (runStart < 0) {
                    runStart = i;
                    runLen = 0;
                }
                runLen++;
                if (runLen > bestLen) {
                    bestLen = runLen;
                    bestStart = runStart;
                }
            } else {
                runStart = -1;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (bestLen > 1 && i == bestStart) {
                sb.append(i == 0 ? "::" : ":");
                i += bestLen - 1;
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Integer.toHexString(groups[i]));
        }
        return sb.length() == 0 ? "::" : sb.toString();
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
            if (!line.contains("LISTEN")) {
                continue;
            }
            String endpoint = localEndpoint(line);
            if (endpoint == null) {
                continue;
            }
            Integer port = portAfterColon(endpoint);
            if (port == null) {
                continue;
            }
            String addr = canonAddr(endpoint.substring(0, endpoint.lastIndexOf(':')));
            Matcher m = SS_PROC.matcher(line);
            if (line.contains("users:((") && m.find()) {
                out.add(new Listener(addr, port, m.group(2), m.group(1)));
            } else {
                // spec-062 Decision 1: an unprivileged `ss` prints the process column only for the
                // login user's own sockets; a foreign-owned LISTEN renders with a BLANK users
                // column. Keep it as an unattributed port (never read a blank column as "no
                // process") — the /proc/net fallback then tries to recover a PID or, failing that,
                // it survives as a low-confidence port.
                out.add(new Listener(addr, port, null, null));
            }
        }
        return out;
    }

    private List<Listener> parseNetstat(List<String> lines) {
        List<Listener> out = new ArrayList<>();
        for (String line : lines) {
            if (!line.contains("LISTEN")) {
                continue;
            }
            String[] tokens = line.trim().split("\\s+");
            String endpoint = null;
            String pidProg = null;
            for (String token : tokens) {
                if (token.contains(":") && !token.contains("*") && portAfterColon(token) != null) {
                    endpoint = token;
                }
                if (token.matches("\\d+/.+")) {
                    pidProg = token;
                }
            }
            if (endpoint == null) {
                continue;
            }
            int port = portAfterColon(endpoint);
            String addr = canonAddr(endpoint.substring(0, endpoint.lastIndexOf(':')));
            if (pidProg != null) {
                int slash = pidProg.indexOf('/');
                out.add(new Listener(addr, port, pidProg.substring(0, slash), pidProg.substring(slash + 1)));
            } else {
                // spec-062 Decision 1: busybox `netstat` (and an `ss` with no `-p` support) omit the
                // process column entirely — keep every LISTEN line as an unattributed port so the
                // fallback can recover it.
                out.add(new Listener(addr, port, null, null));
            }
        }
        return out;
    }

    /** The local endpoint token ({@code addr:port}) of an ss data line, or null. */
    private String localEndpoint(String line) {
        for (String token : line.trim().split("\\s+")) {
            // Skip only the process column (users:((...))). Do NOT skip a "*:PORT" token: an
            // all-interfaces bind — the default for a JVM (Tomcat/Netty) — renders the LOCAL
            // address as "*:8080" in ss. The peer column "*:*" is harmless: portAfterColon
            // returns null for a non-numeric tail, so the local column (first numeric-tail hit)
            // wins.
            if (token.contains(":") && !token.startsWith("users:") && portAfterColon(token) != null) {
                return token;
            }
        }
        return null;
    }

    /** Canonicalise an ss local-address so it matches the {@code /proc/net} decode: {@code *}/{@code [::]} → any-bind. */
    private String canonAddr(String addr) {
        if (addr.equals("*")) {
            return "0.0.0.0";
        }
        if (addr.equals("[::]") || addr.equals("::")) {
            return "::";
        }
        if (addr.startsWith("[") && addr.endsWith("]")) {
            return addr.substring(1, addr.length() - 1);
        }
        return addr;
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

    /** Basename of {@code /proc/<pid>/cwd} (the deploy dir), or null. Name-derivation still
     * reads the basename (spec-055 D5: basename is the identity seed); the full logical path
     * feeds {@link ContextMapper}. */
    private String deployDirName(SshExecutor ssh, SshTarget target, String pid) {
        String path = cwdPath(ssh, target, pid);
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
    private ContextMapper.Context resolveContext(SshExecutor ssh, SshTarget target, String pid) {
        String logical = cwdPath(ssh, target, pid);
        if (logical == null) {
            return null;
        }
        String physical = realCwdPath(ssh, target, pid);
        return ContextMapper.resolveContext(logical, physical, dir -> hasMarkerFile(ssh, target, dir));
    }

    /** The full logical {@code readlink /proc/<pid>/cwd}, or null. */
    private String cwdPath(SshExecutor ssh, SshTarget target, String pid) {
        List<String> out = Probes.lines(ssh, target, List.of("readlink", "/proc/" + pid + "/cwd"));
        if (out.isEmpty()) {
            return null;
        }
        String path = out.get(0).trim();
        return path.isEmpty() ? null : path;
    }

    /** The resolved physical {@code readlink -f /proc/<pid>/cwd} (dedup key, D1), or null. */
    private String realCwdPath(SshExecutor ssh, SshTarget target, String pid) {
        List<String> out = Probes.lines(ssh, target, List.of("readlink", "-f", "/proc/" + pid + "/cwd"));
        if (out.isEmpty()) {
            return null;
        }
        String path = out.get(0).trim();
        return path.isEmpty() ? null : path;
    }

    /** Whether {@code dir} carries any {@link ContextMapper#markerFiles() marker file} ({@code ls -a}). */
    private boolean hasMarkerFile(SshExecutor ssh, SshTarget target, String dir) {
        List<String> entries = Probes.lines(ssh, target, List.of("ls", "-a", dir));
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
    private List<Resolved> nonListeningApps(SshExecutor ssh, SshTarget target, Set<String> seenPids) {
        List<Resolved> out = new ArrayList<>();
        Set<String> emitted = new HashSet<>(seenPids);
        out.addAll(systemdApps(ssh, target, emitted));
        out.addAll(interpreterApps(ssh, target, emitted));
        out.addAll(cronApps(ssh, target));
        return out;
    }

    /** Running {@code *.service} units (spec-056): unit → {@code MainPID} → context, runtime systemd. */
    private List<Resolved> systemdApps(SshExecutor ssh, SshTarget target, Set<String> emitted) {
        List<Resolved> out = new ArrayList<>();
        List<String> units = Probes.lines(ssh, target, List.of(
                "systemctl", "list-units", "--type=service", "--state=running", "--no-legend", "--plain"));
        for (String line : units) {
            String unit = line.trim().split("\\s+")[0];
            if (!SERVICE_UNIT.matcher(unit).matches()) {
                continue;
            }
            String pid = mainPid(ssh, target, unit);
            if (pid == null || !emitted.add(pid)) {
                continue; // no main process (oneshot), or already found via a socket / another sweep
            }
            if (runtimeOf(ssh, target, pid) == Runtime.DOCKER) {
                continue; // Decision 2: a containerised unit's /proc path is never a host context
            }
            String appName = sanitize(unit.substring(0, unit.length() - ".service".length()), 0);
            out.add(new Resolved(Family.GENERIC, appName, 0, Runtime.SYSTEMD.label,
                    resolveContext(ssh, target, pid), "declared app · systemd unit · no port", null));
        }
        return out;
    }

    /** The {@code MainPID} of a running unit ({@code systemctl show}), or null when it has none. */
    private String mainPid(SshExecutor ssh, SshTarget target, String unit) {
        List<String> out = Probes.lines(ssh, target,
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
    private List<Resolved> interpreterApps(SshExecutor ssh, SshTarget target, Set<String> emitted) {
        List<Resolved> out = new ArrayList<>();
        for (String line : Probes.lines(ssh, target, List.of("ps", "-eo", "pid=,args="))) {
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
            if (runtimeOf(ssh, target, pid) == Runtime.DOCKER) {
                continue; // Decision 2
            }
            String appName = sanitize(baseName(script), 0);
            out.add(new Resolved(Family.GENERIC, appName, 0, Runtime.PROCESS.label,
                    resolveContext(ssh, target, pid), "declared app · interpreter process · no port", null));
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
    private List<Resolved> cronApps(SshExecutor ssh, SshTarget target) {
        List<Resolved> out = new ArrayList<>();
        Set<String> seenScripts = new HashSet<>();
        List<String> lines = Probes.lines(ssh, target, List.of("sh", "-c", CRON_PROBE_SCRIPT));
        for (String path : cronScriptPaths(lines)) {
            if (!seenScripts.add(path)) {
                continue;
            }
            ContextMapper.Context context = resolveContextForDir(ssh, target, parentDir(path));
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
    private String verifiedDataDir(SshExecutor ssh, SshTarget target, String pid,
                                   ServiceCatalog.Service service) {
        if (service.dataDirEnvVar() != null) {
            String override = envValue(ssh, target, pid, service.dataDirEnvVar());
            if (override != null) {
                return override;
            }
        }
        return service.dataDir();
    }

    /** A process environment variable's value from {@code /proc/<pid>/environ} (NUL-separated), or null. */
    private String envValue(SshExecutor ssh, SshTarget target, String pid, String var) {
        for (String line : Probes.lines(ssh, target, List.of("cat", "/proc/" + pid + "/environ"))) {
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
    private ContextMapper.Context resolveContextForDir(SshExecutor ssh, SshTarget target, String dir) {
        if (dir == null) {
            return null;
        }
        return ContextMapper.resolveContext(dir, realPath(ssh, target, dir),
                d -> hasMarkerFile(ssh, target, d));
    }

    /** {@code readlink -f <path>} (the physical dedup path), or null when unreadable. */
    private String realPath(SshExecutor ssh, SshTarget target, String path) {
        List<String> out = Probes.lines(ssh, target, List.of("readlink", "-f", path));
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
        List<ProposedAction> actions = new ArrayList<>();
        switch (family) {
            case SPRINGBOOT -> {
                actions.add(endpointProbe("health", "Spring Boot liveness/readiness (/actuator/health).", "/actuator/health"));
                actions.add(endpointProbe("metrics", "JVM + HTTP metrics (/actuator/metrics).", "/actuator/metrics"));
                actions.add(endpointProbe("beans", "Wired beans (/actuator/beans).", "/actuator/beans"));
                actions.add(endpointProbe("info", "Build/runtime facts (/actuator/info).", "/actuator/info"));
                actions.add(processProbe("process", "Threads/fds/liveness from /proc (process-probe supplement)."));
                actions.addAll(footprintProbes());
            }
            case FASTAPI -> actions.addAll(fastApiActions(prometheus));
            case HTTP -> {
                actions.add(endpointProbe("liveness", "HTTP liveness (GET / — no Actuator present).", "/"));
                actions.add(processProbe("process", "Threads/fds/liveness from /proc (process-probe supplement)."));
                actions.addAll(footprintProbes());
            }
            case GENERIC -> {
                actions.add(processProbe("process", "Threads/fds/liveness from /proc (process-probe)."));
                actions.addAll(footprintProbes());
            }
        }
        return new ProposedRecipe(RecipeType.MONITOR, family.recipeName, family.recipeDescription,
                actions, apps);
    }

    private List<ProposedAction> fastApiActions(boolean prometheus) {
        List<ProposedAction> actions = new ArrayList<>();
        actions.add(processProbe("process", "Threads/fds/liveness from /proc across worker PIDs (always probed)."));
        actions.addAll(footprintProbes());
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
     * The app-level CPU check (spec-032, upgraded to a rate in spec-057): a fixed process-tree
     * CPU-<em>rate</em> probe driven by {@link #CPU_RATE_PROBE_SCRIPT}, port as {@code $1}.
     * Named {@code cpu} so it is the first-class app CPU metric-kind the fleet UI reads (the
     * host CPU vitals probe of spec-023 is the host-level counterpart). Read-only, login-user,
     * no {@code sudo} — gated like every action.
     */
    private ProposedAction cpuProbe() {
        return new ProposedAction("cpu",
                "Process-tree CPU-rate (Δ jiffies over a measured interval, the app's PID plus"
                        + " children). Read-only.", false,
                List.of(literal("sh"), literal("-c"), literal(CPU_RATE_PROBE_SCRIPT),
                        literal("sh"), param(ParamBinder.PORT_COMPONENT)),
                List.of(appPortList(APP_LIST_PARAM)));
    }

    /**
     * The app-level RAM check (spec-057): a fixed PSS probe driven by
     * {@link #PSS_RAM_PROBE_SCRIPT}, port as {@code $1}. Named {@code ram} so the fleet UI reads
     * it as the RAM metric-kind (honest PSS, RSS-labelled fallback). Read-only, login-user, no
     * {@code sudo} — the on-demand sudo re-probe below upgrades a degraded reading.
     */
    private ProposedAction ramProbe() {
        return new ProposedAction("ram",
                "Per-context RAM (Σ PSS across the app's PIDs; RSS upper-bound when procfs is"
                        + " denied). Read-only.", false,
                List.of(literal("sh"), literal("-c"), literal(PSS_RAM_PROBE_SCRIPT),
                        literal("sh"), param(ParamBinder.PORT_COMPONENT)),
                List.of(appPortList(APP_LIST_PARAM)));
    }

    /**
     * The app-level disk check (spec-057): a fixed {@code du -sbx} probe driven by
     * {@link #DISK_PROBE_SCRIPT}. Its bound value is the per-context {@code app-folder}, which
     * the run path enriches server-side from the recipe's app_port_list side-data (never a
     * caller-supplied path, S9). Named {@code disk} so the fleet UI reads it as the disk
     * metric-kind. This finally fills the native disk axis spec-049 left null. Read-only,
     * login-user, no {@code sudo}.
     */
    private ProposedAction diskProbe() {
        return new ProposedAction("disk",
                "Per-context disk (du -sbx on the app-folder, root-FS bytes, under-mounts"
                        + " excluded). Read-only.", false,
                List.of(literal("sh"), literal("-c"), literal(DISK_PROBE_SCRIPT),
                        literal("sh"), param(ParamBinder.APP_FOLDER_COMPONENT)),
                List.of(appPortList(APP_LIST_PARAM)));
    }

    /**
     * The on-demand <strong>sudo re-probe</strong> actions (spec-057 Decision 6): {@code sudo}
     * variants of the PSS RAM and disk probes that upgrade a degraded (permission-denied)
     * reading to full fidelity — per-PID {@code smaps_rollup} of other users' processes, and
     * {@code du} into other-user directories. They are ordinary {@link RecipeType#MONITOR}
     * actions {@code sudo=true} (S5: passwordless sudo assumed), approved and run through the
     * ordinary gate; they never auto-run — the client offers them as an explicit control
     * (spec-059). The template is the same fixed script; {@code ParamBinder} prepends
     * {@code sudo -n} at bind time.
     */
    private ProposedAction ramSudoReprobe() {
        return new ProposedAction("ram (sudo re-probe)",
                "Re-probe RAM with sudo — per-PID PSS incl. other users' processes. Read-only.", true,
                List.of(literal("sh"), literal("-c"), literal(PSS_RAM_PROBE_SCRIPT),
                        literal("sh"), param(ParamBinder.PORT_COMPONENT)),
                List.of(appPortList(APP_LIST_PARAM)));
    }

    private ProposedAction diskSudoReprobe() {
        return new ProposedAction("disk (sudo re-probe)",
                "Re-probe disk with sudo — du into other-user directories under the context."
                        + " Read-only.", true,
                List.of(literal("sh"), literal("-c"), literal(DISK_PROBE_SCRIPT),
                        literal("sh"), param(ParamBinder.APP_FOLDER_COMPONENT)),
                List.of(appPortList(APP_LIST_PARAM)));
    }

    /** The four footprint axes (spec-057) every app-monitor family carries, in axis order. */
    private List<ProposedAction> footprintProbes() {
        return List.of(ramProbe(), cpuProbe(), diskProbe(), ramSudoReprobe(), diskSudoReprobe());
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

    /**
     * One listening socket: its local {@code addr}, {@code port}, owning {@code pid} and
     * {@code process} name. {@code pid}/{@code process} are {@code null} for an
     * <strong>unattributed</strong> listener (spec-062 Decision 1): a LISTEN line whose
     * process column {@code ss}/{@code netstat} left blank — an owner the login user cannot
     * read — kept as a port with no owner rather than dropped. The {@code (addr, port)}
     * {@link #key()} is the cross-channel de-duplication key: {@code 127.0.0.1:8080} and
     * {@code 0.0.0.0:8080} are distinct listeners.
     */
    private record Listener(String addr, int port, String pid, String process) {
        boolean attributed() {
            return pid != null;
        }

        String key() {
            return addr + "|" + port;
        }
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
