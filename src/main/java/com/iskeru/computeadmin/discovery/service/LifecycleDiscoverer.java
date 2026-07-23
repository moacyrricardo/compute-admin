package com.iskeru.computeadmin.discovery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.iskeru.computeadmin.discovery.Proposals.allowedSet;
import static com.iskeru.computeadmin.discovery.Proposals.literal;

/**
 * Discovers the lifecycle scripts of an <strong>unmanaged / script-launched</strong>
 * native app and proposes them as gated {@code CUSTOM} start/stop/restart/deploy
 * actions — the script-launched counterpart of {@link SystemdDiscoverer} (spec-050).
 * A systemd app already has spec-026's {@code systemctl} controls and a docker app has
 * {@link DockerDiscoverer}'s; this discoverer serves the app launched by hand or by a
 * {@code run.sh} from its own folder, whose real lifecycle is scripts sitting next to it.
 *
 * <p><strong>Detection (two signals, one fixed read-only scan).</strong> Per native app
 * it (1) resolves the app's PID + folder ({@code appRoot}) — the spec-049 chain,
 * Java-orchestrated over {@code ss}/{@code /proc} as {@link AppMonitorDiscoverer}'s own
 * port→PID→cwd naming already does — then (2) runs a single source-controlled
 * {@link #SCAN_SCRIPT} whose only positional arguments are the resolved
 * {@code <pid> <appRoot>} (discrete argv, POSIX single-quoted by the adapter, never
 * interpolated into the script text — S4). The scan combines PPID-ancestry (a
 * {@code bash …/run.sh} launcher; {@code managedBy} from the cgroup) with a folder scan
 * for conventional lifecycle scripts and build files, and emits one NDJSON line per app:
 * {@code {"appRoot":…,"managedBy":…,"scripts":[{"path","source","proposed",…}]}}.
 *
 * <p><strong>Register found scripts, never inferred run-commands.</strong> A proposed
 * action is <em>only ever</em> a lifecycle <strong>script file that exists on disk</strong>
 * ({@code run.sh}/{@code stop.sh}/…), run verbatim as one absolute {@code LITERAL} path —
 * nothing prepended/appended, no {@code nohup}/{@code setsid}/{@code &} backgrounding
 * wrapper, no shell operator. Build files ({@code Makefile}/{@code pom.xml}/{@code mvnw}/
 * {@code gradlew}/{@code package.json}/compose/{@code Procfile}) are <em>detected only</em>
 * ({@code proposed:false}) and reported in the recipe description as read-only context;
 * they never become a one-click control — inferring {@code mvnw spring-boot:run} /
 * {@code make start} would be a synthesized (usually foreground) command this spec refuses
 * to fabricate.
 *
 * <p><strong>Scope rule.</strong> Runnable actions are proposed only for an app whose
 * {@code managedBy} is {@code script} or {@code bare}. A {@code systemd} app (spec-026) or
 * {@code docker} app ({@link DockerDiscoverer}) is left to its own discoverer — this spec
 * never creates a second way to restart the same app.
 *
 * <p><strong>Actions, the gate &amp; content-pinning.</strong> One proposed recipe per app:
 * {@link RecipeType#CUSTOM}, name {@code lifecycle <app-name>}. Choosing {@code CUSTOM}
 * (not a new enum) makes spec-015 content-pinning apply with zero new code:
 * {@code ApprovalService.approve} {@code sha256sum}s the leading literal path at approval
 * and {@code RunService.run} re-probes before every run. Each action carries the reserved
 * scalar {@code app-name} param (an {@code ALLOWED_SET} of the one owning app — the
 * spec-026 correlation key; no {@code PARAM} argv token references it) and {@code sudo=false}
 * (the folder came from the login user's own process). Every action lands
 * {@code PENDING_APPROVAL} through {@link DiscoveryService}; approval stays UI-only.
 *
 * <p>spec-050; mirrors spec-026; reuses spec-007 (CUSTOM shape) and spec-015 (pinning).
 */
@Component
public class LifecycleDiscoverer implements RecipeDiscoverer {

    /**
     * The fixed, source-controlled lifecycle scan (spec-050). Positional args are the
     * app's {@code <pid>} ({@code $1}) and {@code <appRoot>} ({@code $2}) only — never
     * interpolated into the script text (S4). Purely read-only: it reads
     * {@code /proc/<pid>/cgroup|cmdline|stat} for the {@code managedBy} classification and
     * the PPID-ancestry launcher, {@code test -f}/{@code head}/{@code grep}s the folder for
     * lifecycle scripts and build files, and emits one NDJSON line. It never mutates the
     * box and never runs a discovered script. {@code selfBackgrounds} is a review-only hint
     * (does the script appear to daemonize itself); {@code preview} is the script's
     * {@code head -n5} carried into the approval drawer as context.
     */
    static final String SCAN_SCRIPT = String.join("\n",
            "pid=\"$1\"; root=\"$2\"",
            "esc() { printf '%s' \"$1\" | tr '\\n\\t' '  ' | sed 's/\\\\/\\\\\\\\/g; s/\"/\\\\\"/g'; }",
            "cg=\"\"; [ -r \"/proc/$pid/cgroup\" ] && cg=$(cat \"/proc/$pid/cgroup\" 2>/dev/null)",
            "managedBy=bare",
            "case \"$cg\" in",
            "  *.service) managedBy=systemd ;;",
            "  *docker*|*containerd*|*kubepods*) managedBy=docker ;;",
            "esac",
            "items=\"\"",
            "addscript() {",
            "  sb=null; pv=\"\"; ph=none",
            "  if [ -r \"$1\" ] && [ \"$3\" = true ]; then",
            "    if grep -qE 'nohup|setsid|disown|systemd-run|& *$' \"$1\" 2>/dev/null; then sb=true; else sb=false; fi",
            "    grep -qE '\\$[0-9@*]|getopts' \"$1\" 2>/dev/null && ph=args",
            "    pv=$(head -n5 \"$1\" 2>/dev/null)",
            "  fi",
            "  items=\"$items,{\\\"path\\\":\\\"$(esc \"$1\")\\\",\\\"source\\\":\\\"$2\\\",\\\"proposed\\\":$3,\\\"selfBackgrounds\\\":$sb,\\\"paramsHint\\\":\\\"$ph\\\",\\\"preview\\\":\\\"$(esc \"$pv\")\\\"}\"",
            "}",
            "launcher=\"\"; p=\"$pid\"; n=0",
            "while [ \"$n\" -lt 10 ] && [ \"$p\" -gt 1 ] 2>/dev/null; do",
            "  acmd=$(tr '\\0' ' ' < \"/proc/$p/cmdline\" 2>/dev/null)",
            "  for tok in $acmd; do case \"$tok\" in /*.sh) [ -f \"$tok\" ] && launcher=\"$tok\" ;; esac; done",
            "  [ -n \"$launcher\" ] && break",
            "  p=$(sed 's/.*) //' \"/proc/$p/stat\" 2>/dev/null | awk '{print $2}')",
            "  n=$((n + 1))",
            "done",
            "for f in start.sh run.sh stop.sh kill.sh restart.sh deploy.sh update.sh; do",
            "  [ -f \"$root/$f\" ] && addscript \"$root/$f\" folder true",
            "done",
            "[ -n \"$launcher\" ] && addscript \"$launcher\" ancestry true",
            "for b in Makefile pom.xml mvnw gradlew build.gradle build.gradle.kts settings.gradle settings.gradle.kts Procfile package.json docker-compose.yml docker-compose.yaml compose.yaml; do",
            "  [ -f \"$root/$b\" ] && addscript \"$root/$b\" build-file false",
            "done",
            "printf '{\"appRoot\":\"%s\",\"managedBy\":\"%s\",\"scripts\":[%s]}\\n' \"$(esc \"$root\")\" \"$managedBy\" \"${items#,}\"");

    /** ss line's owning process spec: {@code (("java",pid=1234,fd=10))}. */
    private static final Pattern SS_PROC = Pattern.compile("\\(\"([^\"]+)\",pid=(\\d+)");

    /** The fixed app-name charset (spec-022) a reserved {@code app-name} value must match. */
    private static final Pattern APP_NAME = Pattern.compile(ParamBinder.APP_NAME_PATTERN);

    private final ObjectMapper json;

    public LifecycleDiscoverer(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public DiscovererFamily family() {
        return DiscovererFamily.LIFECYCLE;
    }

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        SshTarget target = Probes.target(machine);
        List<Listener> listeners = listeners(ssh, target);
        if (listeners.isEmpty()) {
            return List.of();
        }
        // One recipe per app (keyed by app-name): a multi-port app resolves to one folder,
        // so scanning once per distinct app-name avoids duplicate lifecycle recipes.
        Map<String, ProposedRecipe> byApp = new LinkedHashMap<>();
        for (Listener listener : listeners) {
            String cmdline = cmdline(ssh, target, listener.pid());
            String appRoot = resolveAppRoot(ssh, target, listener.pid(), cmdline);
            if (appRoot == null || appRoot.isBlank()) {
                continue;
            }
            String appName = appName(appRoot, listener.process(), listener.port());
            if (byApp.containsKey(appName)) {
                continue;
            }
            ProposedRecipe recipe = scanAndPropose(ssh, target, listener.pid(), appRoot, appName);
            if (recipe != null) {
                byApp.put(appName, recipe);
            }
        }
        return new ArrayList<>(byApp.values());
    }

    // --- resolution (the spec-049 chain, Java-orchestrated) -----------------

    /** Listening TCP sockets owned by the login user (the AppMonitorDiscoverer probe shape). */
    private List<Listener> listeners(SshExecutor ssh, SshTarget target) {
        List<Listener> out = new ArrayList<>();
        for (String line : Probes.lines(ssh, target, List.of("ss", "-ltnpH"))) {
            if (!line.contains("users:((")) {
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

    /** The local port from an ss data line's "Local Address:Port" column. */
    private Integer localPort(String line) {
        for (String token : line.trim().split("\\s+")) {
            if (token.contains(":") && !token.startsWith("users:")) {
                String tail = token.substring(token.lastIndexOf(':') + 1);
                try {
                    return Integer.parseInt(tail);
                } catch (NumberFormatException ignored) {
                    // peer column "*:*" etc. — keep scanning.
                }
            }
        }
        return null;
    }

    /** The whitespace-normalised {@code /proc/<pid>/cmdline} (NUL-separated on disk). */
    private String cmdline(SshExecutor ssh, SshTarget target, String pid) {
        List<String> lines = Probes.lines(ssh, target, List.of("cat", "/proc/" + pid + "/cmdline"));
        return String.join(" ", lines).replace('\0', ' ').trim();
    }

    /**
     * The app's folder (the spec-049 {@code appRoot}): the directory of an absolute
     * {@code -jar} artifact when the app is a JVM, else the process cwd
     * ({@code readlink -f /proc/<pid>/cwd}). This is the folder the scan globs for
     * lifecycle scripts; the scan's PPID-ancestry additionally anchors a launcher that
     * may live outside it.
     */
    private String resolveAppRoot(SshExecutor ssh, SshTarget target, String pid, String cmdline) {
        String jar = jarPathAfterDashJar(cmdline);
        if (jar != null && jar.startsWith("/")) {
            int slash = jar.lastIndexOf('/');
            if (slash > 0) {
                return jar.substring(0, slash);
            }
        }
        List<String> cwd = Probes.lines(ssh, target, List.of("readlink", "-f", "/proc/" + pid + "/cwd"));
        return cwd.isEmpty() ? null : cwd.get(0).trim();
    }

    /** The path token right after {@code -jar}, or null (started via -cp + main class / non-java). */
    private String jarPathAfterDashJar(String cmdline) {
        String[] tokens = cmdline.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equals("-jar")) {
                return tokens[i + 1];
            }
        }
        return null;
    }

    /**
     * The correlation app-name: the {@code appRoot} basename (the deploy-dir, which
     * {@link AppMonitorDiscoverer} also uses as a naming fallback so the two tend to
     * agree), coerced to the fixed {@link ParamBinder#APP_NAME_PATTERN} charset; the
     * process name, then {@code app-<port>}, are the fallbacks.
     */
    private String appName(String appRoot, String process, int port) {
        int slash = appRoot.lastIndexOf('/');
        String base = slash >= 0 ? appRoot.substring(slash + 1) : appRoot;
        String fromRoot = sanitize(base);
        if (fromRoot != null) {
            return fromRoot;
        }
        String fromProcess = sanitize(process);
        return fromProcess != null ? fromProcess : "app-" + port;
    }

    /** Coerce a raw label to the fixed app-name charset, or null when nothing survives. */
    private String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("^-+|-+$", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        cleaned = cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
        return APP_NAME.matcher(cleaned).matches() ? cleaned : null;
    }

    // --- scan + verb mapping ------------------------------------------------

    /**
     * Runs {@link #SCAN_SCRIPT} for one app and turns the NDJSON into a proposed
     * {@code lifecycle <app>} recipe, or {@code null} when the scope rule excludes the app
     * (managed by systemd/docker) or nothing runnable was found.
     */
    private ProposedRecipe scanAndPropose(SshExecutor ssh, SshTarget target, String pid,
                                          String appRoot, String appName) {
        ExecResult result = ssh.exec(target,
                List.of("sh", "-c", SCAN_SCRIPT, "sh", pid, appRoot), false);
        if (!result.succeeded() || result.stdout() == null || result.stdout().isBlank()) {
            return null;
        }
        JsonNode root = lastJsonLine(result.stdout());
        if (root == null) {
            return null;
        }
        String managedBy = root.path("managedBy").asText("bare");
        // Scope rule: systemd → spec-026 owns it; docker → DockerDiscoverer. Report only
        // (never propose) so this spec never creates a second way to restart the same app.
        if (!"script".equals(managedBy) && !"bare".equals(managedBy)) {
            return null;
        }

        List<ScriptEntry> found = new ArrayList<>();
        List<String> buildFiles = new ArrayList<>();
        for (JsonNode s : root.path("scripts")) {
            String path = s.path("path").asText(null);
            if (path == null) {
                continue;
            }
            if (!s.path("proposed").asBoolean(false)) {
                int slash = path.lastIndexOf('/');
                buildFiles.add(slash >= 0 ? path.substring(slash + 1) : path);
                continue;
            }
            Boolean selfBg = s.path("selfBackgrounds").isBoolean() ? s.path("selfBackgrounds").asBoolean() : null;
            found.add(new ScriptEntry(path, s.path("source").asText("folder"), selfBg,
                    s.path("paramsHint").asText("none"), s.path("preview").asText("")));
        }

        List<ProposedAction> actions = mapActions(found, appName);
        if (actions.isEmpty()) {
            return null;
        }
        return new ProposedRecipe(RecipeType.CUSTOM, "lifecycle " + appName,
                recipeDescription(appName, buildFiles), actions);
    }

    /**
     * Maps found script files to proposed actions, applying the verb precedence
     * (start.sh &gt; run.sh; stop.sh &gt; kill.sh; deploy.sh &gt; update.sh; a real
     * restart.sh; the ancestry launcher is <em>start</em> only when no folder start
     * candidate won). A script that loses its verb is still proposed under its
     * <strong>basename</strong> (runnable from the drawer, not mapped to a card button).
     * Action names are unique within the recipe by construction.
     */
    private List<ProposedAction> mapActions(List<ScriptEntry> found, String appName) {
        Map<String, ScriptEntry> folderByBase = new LinkedHashMap<>();
        ScriptEntry ancestry = null;
        for (ScriptEntry e : found) {
            if ("ancestry".equals(e.source())) {
                ancestry = e;
            } else {
                folderByBase.putIfAbsent(basename(e.path()), e);
            }
        }

        Map<String, ProposedAction> byName = new LinkedHashMap<>();
        // The verb winners, in card order.
        ScriptEntry start = firstOf(folderByBase, "start.sh", "run.sh");
        if (start == null && ancestry != null) {
            start = ancestry; // the launcher is the start candidate only when the folder had none
        }
        ScriptEntry stop = firstOf(folderByBase, "stop.sh", "kill.sh");
        ScriptEntry restart = folderByBase.get("restart.sh");
        ScriptEntry deploy = firstOf(folderByBase, "deploy.sh", "update.sh");

        putAction(byName, "start", start, appName);
        putAction(byName, "stop", stop, appName);
        putAction(byName, "restart", restart, appName);
        putAction(byName, "deploy", deploy, appName);

        // Losers keep their basename (minus .sh) as the action name — runnable, not a button.
        for (ScriptEntry e : found) {
            if (e == start || e == stop || e == restart || e == deploy) {
                continue;
            }
            String name = stripSh(basename(e.path()));
            putAction(byName, name, e, appName);
        }
        return new ArrayList<>(byName.values());
    }

    private ScriptEntry firstOf(Map<String, ScriptEntry> byBase, String... bases) {
        for (String b : bases) {
            ScriptEntry e = byBase.get(b);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    /** Adds one proposed action for {@code entry} under {@code name}, skipping duplicates. */
    private void putAction(Map<String, ProposedAction> byName, String name, ScriptEntry entry, String appName) {
        if (entry == null || name == null || name.isBlank() || byName.containsKey(name)) {
            return;
        }
        byName.put(name, new ProposedAction(name, actionDescription(entry), false,
                List.of(literal(entry.path())),
                List.of(allowedSet(ParamBinder.APP_NAME_COMPONENT, List.of(appName)))));
    }

    private String actionDescription(ScriptEntry e) {
        StringBuilder sb = new StringBuilder("Runs ").append(e.path()).append(" verbatim (content-pinned).");
        if (Boolean.FALSE.equals(e.selfBackgrounds())) {
            sb.append(" Appears to run in the foreground — Start will hold the run open.");
        } else if (Boolean.TRUE.equals(e.selfBackgrounds())) {
            sb.append(" Appears to background itself.");
        }
        if ("args".equals(e.paramsHint())) {
            sb.append(" This script reads arguments — edit the action to add typed params before approving.");
        }
        if (e.preview() != null && !e.preview().isBlank()) {
            sb.append(" Preview: ").append(e.preview().trim());
        }
        return sb.toString();
    }

    private String recipeDescription(String appName, List<String> buildFiles) {
        String base = "Discovered lifecycle scripts for " + appName + " (script-launched / unmanaged).";
        if (buildFiles.isEmpty()) {
            return base;
        }
        return base + " Build files present (manual only, not proposed): " + String.join(", ", buildFiles) + ".";
    }

    private String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String stripSh(String base) {
        return base.endsWith(".sh") ? base.substring(0, base.length() - 3) : base;
    }

    private JsonNode lastJsonLine(String stdout) {
        JsonNode parsed = null;
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{")) {
                try {
                    parsed = json.readTree(trimmed);
                } catch (Exception ignored) {
                    // Not a JSON line (stray probe noise) — keep the last that parses.
                }
            }
        }
        return parsed;
    }

    /** One listening socket: its port, owning PID, and process name (login user only). */
    private record Listener(int port, String pid, String process) {
    }

    /** One found lifecycle script file the scan proposed as runnable. */
    private record ScriptEntry(String path, String source, Boolean selfBackgrounds,
                               String paramsHint, String preview) {
    }
}
