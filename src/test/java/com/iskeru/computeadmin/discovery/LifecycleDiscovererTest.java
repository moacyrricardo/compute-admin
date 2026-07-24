package com.iskeru.computeadmin.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.discovery.service.LifecycleDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.ssh.ExecResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LifecycleDiscoverer} against a fake executor (spec-050): the {@code ss}→PID→
 * cwd chain resolves a native app's folder, the fixed scan reports the lifecycle scripts
 * next to it, and each <em>found script file</em> becomes a {@code CUSTOM} start/stop/
 * restart/deploy action — run verbatim as one absolute {@code LITERAL} path, carrying the
 * reserved {@code app-name} param. Build files are detected but never turned into runnable
 * actions; systemd/docker-managed apps get no proposals (scope rule); and the discoverer
 * never executes a discovered script — it only ever sends fixed read-only probes.
 *
 * <p>spec-050.
 */
class LifecycleDiscovererTest {

    private final LifecycleDiscoverer discoverer = new LifecycleDiscoverer(new ObjectMapper());

    /** Tokens that would mean a mutating command (a discovered script actually run) was sent. */
    private static final List<String> MUTATING_TOKENS = List.of(
            "nohup", "setsid", "systemctl", "docker", "sudo", "&", "|", ";");

    @Test
    void discover_RunAndKillScripts_ProposesCustomStartStop_CarryingAppNameParamLessLiteralPath() {
        // The demo-harness shape: a bare app with run.sh + kill.sh (and a Makefile) beside it.
        FakeSshExecutor ssh = new FakeSshExecutor(box(
                "/opt/orders/bin/orders --serve", "/opt/orders",
                "{\"appRoot\":\"/opt/orders\",\"managedBy\":\"bare\",\"scripts\":["
                        + "{\"path\":\"/opt/orders/run.sh\",\"source\":\"folder\",\"proposed\":true,\"selfBackgrounds\":true,\"paramsHint\":\"none\",\"preview\":\"nohup ./orders &\"},"
                        + "{\"path\":\"/opt/orders/kill.sh\",\"source\":\"folder\",\"proposed\":true,\"selfBackgrounds\":false,\"paramsHint\":\"args\",\"preview\":\"kill $1\"},"
                        + "{\"path\":\"/opt/orders/Makefile\",\"source\":\"build-file\",\"proposed\":false,\"selfBackgrounds\":null,\"paramsHint\":\"none\",\"preview\":\"\"}"
                        + "]}"));

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).hasSize(1);
        ProposedRecipe recipe = recipes.get(0);
        assertThat(recipe.type()).isEqualTo(RecipeType.CUSTOM);
        assertThat(recipe.name()).isEqualTo("lifecycle orders");
        // run.sh → start (no start.sh), kill.sh → stop (no stop.sh); the Makefile is NOT runnable.
        assertThat(recipe.actions()).extracting(ProposedAction::name).containsExactly("start", "stop");

        ProposedAction start = action(recipe, "start");
        // Runs the found file VERBATIM: one absolute LITERAL path, no args, sudo=false.
        assertThat(start.sudo()).isFalse();
        assertThat(start.argTokens()).hasSize(1);
        assertThat(start.argTokens().get(0).kind()).isEqualTo(TokenKind.LITERAL);
        assertThat(start.argTokens().get(0).value()).isEqualTo("/opt/orders/run.sh");
        assertThat(action(recipe, "stop").argTokens().get(0).value()).isEqualTo("/opt/orders/kill.sh");

        // The one param is the reserved scalar app-name (ALLOWED_SET of the one owning app) —
        // correlation-only, NOT referenced by any PARAM argv token (spec-026 redeploy shape).
        ParamDefInput appName = param(start, "app-name");
        assertThat(appName.kind()).isEqualTo(ParamKind.ALLOWED_SET);
        assertThat(appName.allowedValues()).containsExactly("orders");
        assertThat(start.argTokens()).noneMatch(t -> t.kind() == TokenKind.PARAM);

        // The Makefile is REPORTED (recipe description) but never a runnable action.
        assertThat(recipe.description()).contains("Makefile");
        assertThat(recipe.actions()).extracting(ProposedAction::name)
                .doesNotContain("make", "Makefile");
    }

    @Test
    void discover_VerbMappingAndPrecedence_StartWinsOverRun_LosersKeepBasename() {
        FakeSshExecutor ssh = new FakeSshExecutor(box(
                "/srv/app/bin/app", "/srv/app",
                folderScripts("/srv/app",
                        "start.sh", "run.sh", "stop.sh", "kill.sh", "restart.sh", "deploy.sh")));

        ProposedRecipe recipe = discoverer.discover(machine(), ssh).get(0);

        // start.sh wins `start`; run.sh is a loser proposed under its basename `run`.
        // stop.sh wins `stop`; kill.sh loses to `kill`. restart.sh → restart; deploy.sh → deploy.
        assertThat(recipe.actions()).extracting(ProposedAction::name)
                .containsExactlyInAnyOrder("start", "stop", "restart", "deploy", "run", "kill");
        assertThat(action(recipe, "start").argTokens().get(0).value()).isEqualTo("/srv/app/start.sh");
        assertThat(action(recipe, "run").argTokens().get(0).value()).isEqualTo("/srv/app/run.sh");
        assertThat(action(recipe, "stop").argTokens().get(0).value()).isEqualTo("/srv/app/stop.sh");
        assertThat(action(recipe, "kill").argTokens().get(0).value()).isEqualTo("/srv/app/kill.sh");
    }

    @Test
    void discover_ScanIsFixedLiteral_WithOnlyPidAndAppRootPositionalArgs_AndProposedActionsAreOperatorFree() {
        FakeSshExecutor ssh = new FakeSshExecutor(box(
                "/opt/orders/bin/orders", "/opt/orders",
                folderScripts("/opt/orders", "run.sh", "kill.sh")));

        ProposedRecipe recipe = discoverer.discover(machine(), ssh).get(0);

        // The scan probe is `sh -c <SCAN> sh <pid> <appRoot>`: pid + appRoot are the ONLY
        // positional args, never interpolated into the fixed script body (S4).
        List<String> scan = ssh.commands.stream()
                .filter(c -> c.size() == 6 && "sh".equals(c.get(0)) && "-c".equals(c.get(1)))
                .findFirst().orElseThrow();
        assertThat(scan.get(3)).isEqualTo("sh");
        assertThat(scan.get(4)).isEqualTo("1000");          // pid
        assertThat(scan.get(5)).isEqualTo("/opt/orders");   // appRoot
        String script = scan.get(2);
        assertThat(script).contains("pid=\"$1\"").contains("root=\"$2\"")
                .contains("managedBy").contains("/proc/").contains("start.sh run.sh");

        // Choosing CUSTOM makes spec-015 content-pinning apply with zero new code.
        assertThat(recipe.type()).isEqualTo(RecipeType.CUSTOM);

        // Every proposed action is a single absolute LITERAL path — no prepended tool, no
        // shell operator, no synthesized backgrounding wrapper (the hard constraint).
        assertThat(recipe.actions()).allSatisfy(a -> {
            assertThat(a.argTokens()).hasSize(1);
            ArgTokenInput tok = a.argTokens().get(0);
            assertThat(tok.kind()).isEqualTo(TokenKind.LITERAL);
            assertThat(tok.value()).startsWith("/").matches("/[A-Za-z0-9._/-]+");
            MUTATING_TOKENS.forEach(m -> assertThat(tok.value()).doesNotContain(m));
        });
    }

    @Test
    void discover_SystemdManagedApp_ProposesNothing() {
        // A systemd-managed app already has spec-026's controls; even with folder scripts the
        // scope rule reports (never proposes) — this spec never creates a second restart path.
        FakeSshExecutor ssh = new FakeSshExecutor(box(
                "/opt/orders/orders", "/opt/orders",
                "{\"appRoot\":\"/opt/orders\",\"managedBy\":\"systemd\",\"scripts\":["
                        + "{\"path\":\"/opt/orders/run.sh\",\"source\":\"folder\",\"proposed\":true,\"selfBackgrounds\":true,\"paramsHint\":\"none\",\"preview\":\"\"}"
                        + "]}"));

        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    @Test
    void discover_DockerManagedApp_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(box(
                "/opt/orders/orders", "/opt/orders",
                "{\"appRoot\":\"/opt/orders\",\"managedBy\":\"docker\",\"scripts\":["
                        + "{\"path\":\"/opt/orders/run.sh\",\"source\":\"folder\",\"proposed\":true,\"selfBackgrounds\":true,\"paramsHint\":\"none\",\"preview\":\"\"}"
                        + "]}"));

        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    @Test
    void discover_OnlyBuildFilesNoScripts_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(box(
                "/build/app/bin/app", "/build/app",
                "{\"appRoot\":\"/build/app\",\"managedBy\":\"bare\",\"scripts\":["
                        + "{\"path\":\"/build/app/pom.xml\",\"source\":\"build-file\",\"proposed\":false,\"selfBackgrounds\":null,\"paramsHint\":\"none\",\"preview\":\"\"},"
                        + "{\"path\":\"/build/app/mvnw\",\"source\":\"build-file\",\"proposed\":false,\"selfBackgrounds\":null,\"paramsHint\":\"none\",\"preview\":\"\"}"
                        + "]}"));

        // Build files are never inferred into a run-command → nothing runnable → no recipe.
        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    @Test
    void discover_OnlyEverIssuesReadOnlyProbes_NeverRunsADiscoveredScript() {
        FakeSshExecutor ssh = new FakeSshExecutor(box(
                "/opt/orders/orders", "/opt/orders",
                folderScripts("/opt/orders", "run.sh", "kill.sh")));

        discoverer.discover(machine(), ssh);

        assertThat(ssh.commands).isNotEmpty();
        // First probe is the listener enumeration; the scan is `sh -c` read-only; and NO
        // command ever executes a discovered script path (run.sh/kill.sh) — detection only.
        assertThat(ssh.commands.get(0)).containsExactly("ss", "-ltnpH");
        assertThat(ssh.commands).noneSatisfy(argv ->
                assertThat(argv).anyMatch(t -> t.endsWith("/run.sh") || t.endsWith("/kill.sh")));
    }

    // --- canned box ---------------------------------------------------------

    /** A native listener on port 9000 (pid 1000) with the given cmdline, cwd, and scan NDJSON. */
    private Function<List<String>, ExecResult> box(String cmdline, String cwd, String ndjson) {
        String ss = "LISTEN 0 128 0.0.0.0:9000 0.0.0.0:* users:((\"orders\",pid=1000,fd=10))";
        return argv -> {
            if (argv.equals(List.of("ss", "-ltnpH"))) {
                return ok(ss);
            }
            if (argv.equals(List.of("cat", "/proc/1000/cmdline"))) {
                return ok(cmdline);
            }
            if (argv.equals(List.of("readlink", "-f", "/proc/1000/cwd"))) {
                return ok(cwd);
            }
            if (argv.size() == 6 && "sh".equals(argv.get(0)) && "-c".equals(argv.get(1))) {
                return ok(ndjson);
            }
            return notFound();
        };
    }

    /** A scan NDJSON reporting the named lifecycle scripts under {@code root}, all proposed. */
    private static String folderScripts(String root, String... names) {
        StringBuilder sb = new StringBuilder("{\"appRoot\":\"").append(root)
                .append("\",\"managedBy\":\"bare\",\"scripts\":[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"path\":\"").append(root).append('/').append(names[i])
                    .append("\",\"source\":\"folder\",\"proposed\":true,")
                    .append("\"selfBackgrounds\":false,\"paramsHint\":\"none\",\"preview\":\"\"}");
        }
        return sb.append("]}").toString();
    }

    private static ParamDefInput param(ProposedAction action, String paramName) {
        return action.paramDefs().stream()
                .filter(p -> p.name().equals(paramName))
                .findFirst().orElseThrow();
    }

    private static ProposedAction action(ProposedRecipe recipe, String name) {
        return recipe.actions().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static Machine machine() {
        Machine machine = new Machine();
        machine.setHost("host");
        machine.setPort(22);
        machine.setLoginUser("deploy");
        return machine;
    }
}
