package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.discovery.service.AppMonitorDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.ssh.ExecResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AppMonitorDiscoverer} against a fake executor (spec-025): the {@code ss}→PID→
 * cmdline classifier routes a java {@code -jar} listener to {@code springboot monitor},
 * a uvicorn listener to {@code fastapi monitor}, and an unclassifiable binary to
 * {@code generic app monitor}; each recipe is pre-filled with its {@code (app-name,
 * port)} items; a container-hosted app gets {@code runtime = docker} and a
 * container-matching {@code appName} (the spec-022 double-detection link); and the
 * discoverer only ever issues read-only probes.
 *
 * <p>spec-025.
 */
class AppMonitorDiscovererTest {

    private final AppMonitorDiscoverer discoverer = new AppMonitorDiscoverer();

    /** Verbs that would mean a mutating command — not a read-only probe — was sent. */
    private static final List<String> MUTATING_TOKENS = List.of(
            "restart", "reload", "stop", "start", "kill", "rm", "systemctl", "docker", "sudo");

    @Test
    void discover_ClassifiesEachListener_AndRoutesToItsFamilyRecipe() {
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).extracting(ProposedRecipe::name)
                .containsExactlyInAnyOrder("springboot monitor", "fastapi monitor", "generic app monitor");
        assertThat(recipes).allSatisfy(r -> assertThat(r.type()).isEqualTo(RecipeType.MONITOR));

        // java -jar /opt/orders.jar → springboot monitor, pre-filled (orders, 8080).
        // The app-level cpu check (spec-032) follows the process probe in every family.
        ProposedRecipe springboot = recipe(recipes, "springboot monitor");
        assertThat(springboot.actions()).extracting(ProposedAction::name)
                .containsExactly("health", "metrics", "beans", "info", "process", "cpu", "footprint");
        assertThat(springboot.appPortList())
                .containsExactly(new AppPortItem("orders", 8080, "process"));

        // python3 uvicorn billing.main:app → fastapi monitor, pre-filled (billing, 8000).
        // No Prometheus on /metrics → the metrics action is not proposed (process + cpu + health).
        ProposedRecipe fastapi = recipe(recipes, "fastapi monitor");
        assertThat(fastapi.actions()).extracting(ProposedAction::name)
                .containsExactly("process", "cpu", "health", "footprint");
        assertThat(fastapi.appPortList())
                .containsExactly(new AppPortItem("billing", 8000, "process"));

        // an unclassifiable daemon → generic app monitor, process + cpu probes only.
        ProposedRecipe generic = recipe(recipes, "generic app monitor");
        assertThat(generic.actions()).extracting(ProposedAction::name).containsExactly("process", "cpu", "footprint");
        assertThat(generic.appPortList())
                .containsExactly(new AppPortItem("mydaemon", 5000, "process"));
    }

    @Test
    void discover_ContainerHostedApp_StampsDockerRuntimeAndContainerName() {
        FakeSshExecutor ssh = new FakeSshExecutor(dockerisedSpringBoot());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        // The cgroup resolves to container "orders-api", so the pre-filled item reconciles
        // with DockerDiscoverer's container name and is stamped runtime = docker.
        ProposedRecipe springboot = recipe(recipes, "springboot monitor");
        assertThat(springboot.appPortList())
                .containsExactly(new AppPortItem("orders-api", 8080, "docker"));
    }

    @Test
    void discover_FastApiWithPrometheus_ProposesMetricsAction() {
        FakeSshExecutor ssh = new FakeSshExecutor(fastApiWithMetrics());

        ProposedRecipe fastapi = recipe(discoverer.discover(machine(), ssh), "fastapi monitor");

        // /metrics responds → the optional Prometheus probe is proposed alongside health.
        assertThat(fastapi.actions()).extracting(ProposedAction::name)
                .containsExactly("process", "cpu", "health", "metrics", "footprint");
    }

    @Test
    void discover_NoListeners_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());
        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    @Test
    void discover_AllInterfacesBind_RendersAsStarPort_IsStillDiscovered() {
        // Regression: a JVM binding all interfaces (the Spring Boot default) has its
        // LOCAL address printed by ss as "*:8080", not "0.0.0.0:8080". The port parser
        // must not discard the "*" token — otherwise every default-bind Spring Boot app
        // (e.g. a `nohup java -jar app.jar` deploy) is invisible to app-monitor discovery.
        FakeSshExecutor ssh = new FakeSshExecutor(allInterfacesSpringBoot());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");

        assertThat(springboot.appPortList())
                .containsExactly(new AppPortItem("app", 8080, "process"));
    }

    @Test
    void discover_SpringBootWithoutActuator_FallsBackToHttpLivenessMonitor() {
        // No /actuator/* responds → it must NOT become a springboot monitor of dead
        // probes; it falls back to an http app monitor (liveness GET / + process).
        FakeSshExecutor ssh = new FakeSshExecutor(actuatorlessSpringBoot());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).extracting(ProposedRecipe::name).containsExactly("http app monitor");
        ProposedRecipe http = recipe(recipes, "http app monitor");
        assertThat(http.actions()).extracting(ProposedAction::name)
                .containsExactly("liveness", "process", "cpu", "footprint");
        assertThat(http.appPortList()).containsExactly(new AppPortItem("app", 8080, "process"));
    }

    @Test
    void discover_JavaWithAgentAndClasspathJars_NamesFromExecutableJar() {
        // -javaagent:/...newrelic.jar and -cp /...common.jar precede -jar; the app name
        // must come from the EXECUTABLE jar (orders), not the agent/classpath jars.
        FakeSshExecutor ssh = new FakeSshExecutor(agentAndClasspathSpringBoot());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");

        assertThat(springboot.appPortList())
                .containsExactly(new AppPortItem("orders", 8080, "process"));
    }

    @Test
    void discover_GenericJarName_NamesFromDeployDir() {
        // -jar /opt/app.jar → generic "app"; /proc/<pid>/cwd (the deploy dir) names it.
        FakeSshExecutor ssh = new FakeSshExecutor(genericJarDeployDir());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");

        assertThat(springboot.appPortList())
                .containsExactly(new AppPortItem("birthday-rsvp", 8080, "process"));
    }

    @Test
    void discover_GenericJarAndDir_NamesFromManifestStartClass() {
        // Generic jar AND generic cwd → the fat jar's Start-Class (not the boot-loader
        // Main-Class): package dropped, "Application" stripped, camelCase → kebab.
        FakeSshExecutor ssh = new FakeSshExecutor(genericJarManifest());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");

        assertThat(springboot.appPortList())
                .containsExactly(new AppPortItem("payment-gateway", 8080, "process"));
    }

    @Test
    void discover_AppLevelCpuProbe_IsBoundedReadOnlyProcessTreeProbe() {
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");
        ProposedAction cpu = springboot.actions().stream()
                .filter(a -> a.name().equals("cpu")).findFirst().orElseThrow();

        // Read-only, login-user (no sudo), and fans out over the app-port list like the
        // other probes: the only bound param is the validated port (spec-032, S4-safe).
        assertThat(cpu.sudo()).isFalse();
        assertThat(cpu.paramDefs()).extracting(p -> p.kind())
                .containsExactly(com.iskeru.computeadmin.recipe.model.ParamKind.APP_PORT_LIST);
        assertThat(cpu.argTokens()).anySatisfy(t ->
                assertThat(t.value()).isEqualTo("port"));

        // The script is a fixed process-tree CPU read (ps, PID + children) — never mutating.
        String script = cpu.argTokens().stream().map(t -> t.value()).reduce("", (a, b) -> a + "\n" + b);
        assertThat(script).contains("ps ").contains("--ppid").contains("pcpu");
        MUTATING_TOKENS.forEach(tok -> assertThat(script).doesNotContain(" " + tok + " "));
    }

    @Test
    void discover_EveryAppMonitorFamily_ProposesTheFootprintAction() {
        // spec-049: the app-folder/footprint probe is appended to EVERY family so the UI
        // can learn where each native app lives + how big it is — regardless of framework.
        FakeSshExecutor mixed = new FakeSshExecutor(mixedBox());
        List<ProposedRecipe> recipes = discoverer.discover(machine(), mixed);
        assertThat(recipes).allSatisfy(r ->
                assertThat(r.actions()).extracting(ProposedAction::name).contains("footprint"));

        // …including the actuator-less HTTP family (its own routing branch).
        ProposedRecipe http = recipe(
                discoverer.discover(machine(), new FakeSshExecutor(actuatorlessSpringBoot())), "http app monitor");
        assertThat(http.actions()).extracting(ProposedAction::name).contains("footprint");
    }

    @Test
    void discover_FootprintProbe_IsFixedReadOnlyFsWalkWithPortSoleParam() {
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");
        ProposedAction footprint = springboot.actions().stream()
                .filter(a -> a.name().equals("footprint")).findFirst().orElseThrow();

        // Read-only, login-user (no sudo), and fans out over the app-port list like the
        // other probes: the only bound param is the validated port (spec-049, S4-safe).
        assertThat(footprint.sudo()).isFalse();
        assertThat(footprint.paramDefs()).extracting(p -> p.kind())
                .containsExactly(com.iskeru.computeadmin.recipe.model.ParamKind.APP_PORT_LIST);
        assertThat(footprint.argTokens()).anySatisfy(t -> assertThat(t.value()).isEqualTo("port"));

        // The script body is a fixed, source-controlled literal (identical across every
        // family) — a read-only /proc + fs walk (ss, readlink, stat, du) that never mutates.
        ProposedAction generic = recipe(discoverer.discover(machine(), ssh), "generic app monitor")
                .actions().stream().filter(a -> a.name().equals("footprint")).findFirst().orElseThrow();
        String script = footprint.argTokens().stream().map(t -> t.value()).reduce("", (a, b) -> a + "\n" + b);
        String genericScript = generic.argTokens().stream().map(t -> t.value()).reduce("", (a, b) -> a + "\n" + b);
        assertThat(script).isEqualTo(genericScript); // one fixed template, per S4
        assertThat(script).contains("ss -ltnpH").contains("readlink -f").contains("stat -c")
                .contains("du -sb").contains("/proc/");
        MUTATING_TOKENS.forEach(tok -> assertThat(script).doesNotContain(" " + tok + " "));
        // The port is passed as $1, never interpolated into the script string at authoring.
        assertThat(script).contains("port=\"$1\"");
    }

    @Test
    void discover_OnlyEverIssuesReadOnlyProbes() {
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());

        discoverer.discover(machine(), ssh);

        assertThat(ssh.commands).isNotEmpty();
        assertThat(ssh.commands).allSatisfy(argv -> {
            // Only ss / netstat / cat / readlink / unzip / curl GETs — never a mutating verb.
            assertThat(argv.get(0)).isIn("ss", "netstat", "cat", "readlink", "unzip", "curl");
            assertThat(argv).doesNotContainAnyElementsOf(MUTATING_TOKENS);
        });
        // The classifier really read /proc for the discovered PID.
        assertThat(ssh.commands).contains(List.of("cat", "/proc/1000/cmdline"));
    }

    // --- canned boxes -------------------------------------------------------

    private Function<List<String>, ExecResult> mixedBox() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:8080       0.0.0.0:*     users:((\"java\",pid=1000,fd=10))",
                "LISTEN 0      128        127.0.0.1:8000       0.0.0.0:*     users:((\"python3\",pid=2000,fd=6))",
                "LISTEN 0      128             [::]:5000          [::]:*     users:((\"mydaemon\",pid=3000,fd=7))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" -> ok("java -jar /opt/orders.jar");
            case "cat /proc/2000/cmdline" -> ok("python3 /usr/bin/uvicorn billing.main:app");
            case "cat /proc/3000/cmdline" -> ok("/usr/local/bin/mydaemon --serve");
            case "cat /proc/1000/cgroup", "cat /proc/2000/cgroup", "cat /proc/3000/cgroup" ->
                    ok("0::/user.slice/user-1000.slice/session-3.scope");
            case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health" -> ok("{\"status\":\"UP\"}");
            default -> notFound(); // curl -sf .../metrics → no Prometheus
        };
    }

    private Function<List<String>, ExecResult> dockerisedSpringBoot() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:8080       0.0.0.0:*     users:((\"java\",pid=1000,fd=10))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" -> ok("java -jar /app/app.jar");
            case "cat /proc/1000/cgroup" -> ok("0::/docker/orders-api");
            case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health" -> ok("{\"status\":\"UP\"}");
            default -> notFound();
        };
    }

    private Function<List<String>, ExecResult> fastApiWithMetrics() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128        127.0.0.1:8000       0.0.0.0:*     users:((\"gunicorn\",pid=2000,fd=6))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/2000/cmdline" -> ok("gunicorn shop.main:app");
            case "cat /proc/2000/cgroup" -> ok("0::/user.slice/session-3.scope");
            case "curl -sf -m 2 http://127.0.0.1:8000/metrics" -> ok("# HELP up\n# TYPE up gauge\nup 1");
            default -> notFound();
        };
    }

    private Function<List<String>, ExecResult> allInterfacesSpringBoot() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      100                *:8080             *:*     users:((\"java\",pid=1000,fd=100))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" -> ok("java -Xmx384m -jar app.jar --spring.profiles.active=production");
            case "cat /proc/1000/cgroup" -> ok("0::/user.slice/user-1000.slice/session-3.scope");
            case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health" -> ok("{\"status\":\"UP\"}");
            default -> notFound();
        };
    }

    // A Spring Boot app shipped WITHOUT actuator: /actuator/health does not answer.
    private Function<List<String>, ExecResult> actuatorlessSpringBoot() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      100                *:8080             *:*     users:((\"java\",pid=1000,fd=100))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" -> ok("java -Xmx384m -jar app.jar --spring.profiles.active=production");
            case "cat /proc/1000/cgroup" -> ok("0::/user.slice/user-1000.slice/session-3.scope");
            default -> notFound(); // no /actuator/health, no /metrics
        };
    }

    private Function<List<String>, ExecResult> agentAndClasspathSpringBoot() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:8080       0.0.0.0:*     users:((\"java\",pid=1000,fd=10))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" ->
                    ok("java -javaagent:/opt/newrelic/newrelic.jar -cp /app/lib/common.jar -jar /app/orders.jar --server.port=8080");
            case "cat /proc/1000/cgroup" -> ok("0::/user.slice/user-1000.slice/session-3.scope");
            case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health" -> ok("{\"status\":\"UP\"}");
            default -> notFound();
        };
    }

    private Function<List<String>, ExecResult> genericJarDeployDir() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:8080       0.0.0.0:*     users:((\"java\",pid=1000,fd=10))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" -> ok("java -jar /opt/app.jar");
            case "cat /proc/1000/cgroup" -> ok("0::/user.slice/user-1000.slice/session-3.scope");
            case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health" -> ok("{\"status\":\"UP\"}");
            case "readlink /proc/1000/cwd" -> ok("/opt/birthday-rsvp");
            default -> notFound();
        };
    }

    private Function<List<String>, ExecResult> genericJarManifest() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:8080       0.0.0.0:*     users:((\"java\",pid=1000,fd=10))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" -> ok("java -jar /srv/app/app.jar");
            case "cat /proc/1000/cgroup" -> ok("0::/user.slice/user-1000.slice/session-3.scope");
            case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health" -> ok("{\"status\":\"UP\"}");
            case "readlink /proc/1000/cwd" -> ok("/srv/app");
            case "unzip -p /srv/app/app.jar META-INF/MANIFEST.MF" -> ok(String.join("\n",
                    "Manifest-Version: 1.0",
                    "Main-Class: org.springframework.boot.loader.launch.JarLauncher",
                    "Start-Class: com.acme.PaymentGatewayApplication"));
            default -> notFound();
        };
    }

    private static ProposedRecipe recipe(List<ProposedRecipe> recipes, String name) {
        return recipes.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static Machine machine() {
        Machine machine = new Machine();
        machine.setHost("host");
        machine.setPort(22);
        machine.setLoginUser("deploy");
        return machine;
    }
}
