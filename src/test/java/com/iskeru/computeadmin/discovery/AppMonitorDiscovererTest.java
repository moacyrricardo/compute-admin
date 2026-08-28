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
import static org.assertj.core.api.Assertions.tuple;

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

    /**
     * Verbs that would mean a mutating command — not a read-only probe — was sent. These are
     * mutating <em>sub-commands</em>, not binary names: {@code systemctl}/{@code ps} are now
     * issued for their read-only lenses ({@code list-units}, {@code show}, {@code -eo}), so the
     * guard bans the action verbs a mutating call would carry, never the binary itself.
     */
    private static final List<String> MUTATING_TOKENS = List.of(
            "restart", "reload", "stop", "start", "kill", "rm", "enable", "disable", "sudo");

    @Test
    void discover_ClassifiesEachListener_AndRoutesToItsFamilyRecipe() {
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).extracting(ProposedRecipe::name)
                .containsExactlyInAnyOrder("springboot monitor", "fastapi monitor", "generic app monitor");
        assertThat(recipes).allSatisfy(r -> assertThat(r.type()).isEqualTo(RecipeType.MONITOR));

        // java -jar /opt/orders.jar → springboot monitor, pre-filled (orders, 8080).
        // The four footprint axes (spec-057) follow the process probe in every family:
        // ram (PSS), cpu (rate), disk (du), + the two on-demand sudo re-probes.
        ProposedRecipe springboot = recipe(recipes, "springboot monitor");
        assertThat(springboot.actions()).extracting(ProposedAction::name)
                .containsExactly("health", "metrics", "beans", "info", "process",
                        "ram", "cpu", "disk", "ram (sudo re-probe)", "disk (sudo re-probe)");
        assertThat(springboot.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime)
                .containsExactly(tuple("orders", 8080, "process"));

        // python3 uvicorn billing.main:app → fastapi monitor, pre-filled (billing, 8000).
        // No Prometheus on /metrics → the metrics action is not proposed.
        ProposedRecipe fastapi = recipe(recipes, "fastapi monitor");
        assertThat(fastapi.actions()).extracting(ProposedAction::name)
                .containsExactly("process", "ram", "cpu", "disk",
                        "ram (sudo re-probe)", "disk (sudo re-probe)", "health");
        assertThat(fastapi.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime)
                .containsExactly(tuple("billing", 8000, "process"));

        // an unclassifiable daemon → generic app monitor, process + the footprint axes.
        ProposedRecipe generic = recipe(recipes, "generic app monitor");
        assertThat(generic.actions()).extracting(ProposedAction::name)
                .containsExactly("process", "ram", "cpu", "disk",
                        "ram (sudo re-probe)", "disk (sudo re-probe)");
        assertThat(generic.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime)
                .containsExactly(tuple("mydaemon", 5000, "process"));
    }

    @Test
    void discover_ListeningApp_CarriesPortProvenanceSourceNote() {
        // Every listening app records which sweep branch found it (spec-056): a host app
        // folder discovered via its port. The note names the port, never a path (S9).
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");
        AppPortItem item = springboot.appPortList().get(0);

        assertThat(item.sourceNote()).isEqualTo("app folder · discovered via port :8080");
    }

    @Test
    void discover_ContainerRuntimeListener_SourceNoteNamesTheContainerBranch() {
        // A container PID that still owns a host-visible socket is labelled a container in
        // its provenance (its host /proc path is never mapped — spec-056 Decision 2/4).
        FakeSshExecutor ssh = new FakeSshExecutor(dockerisedSpringBoot());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");
        AppPortItem item = springboot.appPortList().get(0);

        assertThat(item.sourceNote()).isEqualTo("container · discovered via port :8080");
    }

    @Test
    void discover_ContainerHostedApp_StampsDockerRuntimeAndContainerName() {
        FakeSshExecutor ssh = new FakeSshExecutor(dockerisedSpringBoot());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        // The cgroup resolves to container "orders-api", so the pre-filled item reconciles
        // with DockerDiscoverer's container name and is stamped runtime = docker.
        ProposedRecipe springboot = recipe(recipes, "springboot monitor");
        assertThat(springboot.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime)
                .containsExactly(tuple("orders-api", 8080, "docker"));
    }

    @Test
    void discover_FastApiWithPrometheus_ProposesMetricsAction() {
        FakeSshExecutor ssh = new FakeSshExecutor(fastApiWithMetrics());

        ProposedRecipe fastapi = recipe(discoverer.discover(machine(), ssh), "fastapi monitor");

        // /metrics responds → the optional Prometheus probe is proposed after health.
        assertThat(fastapi.actions()).extracting(ProposedAction::name)
                .containsExactly("process", "ram", "cpu", "disk",
                        "ram (sudo re-probe)", "disk (sudo re-probe)", "health", "metrics");
    }

    @Test
    void discover_DockerProxyListener_IsSkippedNotMappedAsANativeApp() {
        // A published container port shows on the host as a docker-proxy listener (iptables
        // DNAT). Decision 4: it must NOT become a native app via docker-proxy's /proc path —
        // the Docker branch owns that port's truth. Only the real native app is emitted.
        FakeSshExecutor ssh = new FakeSshExecutor(dockerProxyAndNativeApp());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).extracting(ProposedRecipe::name).containsExactly("springboot monitor");
        assertThat(recipe(recipes, "springboot monitor").appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port)
                .containsExactly(tuple("orders", 8080));
        // The proxy PID's /proc is never read (the skip short-circuits before any probe).
        assertThat(ssh.commands).doesNotContain(List.of("cat", "/proc/4000/cmdline"));
    }

    @Test
    void discover_NoListeners_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());
        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    @Test
    void discover_NonListeningApps_AreUnionedWithSentinelPortAndProvenance() {
        // Decision 3: the systemd worker, the interpreter ETL process and the cron script own
        // no listening socket, so they are invisible to the port sweep. They are unioned into
        // the app map with the sentinel port 0 and a per-branch sourceNote; each is mapped to
        // its owning context. The already-listening java PID (1000) is NOT re-emitted by the
        // interpreter scan (dedup by PID).
        FakeSshExecutor ssh = new FakeSshExecutor(nonListeningBox());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).extracting(ProposedRecipe::name)
                .contains("springboot monitor", "generic app monitor");
        ProposedRecipe generic = recipe(recipes, "generic app monitor");
        assertThat(generic.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime,
                        AppPortItem::sourceNote)
                .containsExactlyInAnyOrder(
                        tuple("worker", 0, "systemd", "declared app · systemd unit · no port"),
                        tuple("run.py", 0, "process", "declared app · interpreter process · no port"),
                        tuple("nightly.sh", 0, "process", "declared app · cron-launched · no port"));
        // The non-listening apps carry their mapped context identity (spec-055 seam).
        assertThat(generic.appPortList()).filteredOn(i -> i.appName().equals("worker"))
                .singleElement().extracting(AppPortItem::contextKey).isEqualTo("/opt/lab/worker");
        // The listening java app stays in springboot monitor, never duplicated into generic.
        assertThat(generic.appPortList()).extracting(AppPortItem::appName).doesNotContain("orders");
    }

    @Test
    void discover_OnlyNonListeningApps_StillProposesAGenericMonitor() {
        // A box with no listening sockets at all (workers/cron only) must not fall through the
        // old "no listeners → nothing" early return: the non-listening sweep still discovers it.
        FakeSshExecutor ssh = new FakeSshExecutor(onlyNonListeningBox());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).extracting(ProposedRecipe::name).containsExactly("generic app monitor");
        assertThat(recipe(recipes, "generic app monitor").appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port)
                .containsExactly(tuple("worker", 0));
    }

    @Test
    void discover_NonListeningContainerUnit_IsSkippedByCgroupGuard() {
        // Decision 2: a systemd unit whose MainPID cgroup routes to Docker is a containerised
        // app — its host /proc path is overlayfs, never a host context — so the non-listening
        // sweep drops it (the Docker branch owns it), rather than mapping it natively.
        FakeSshExecutor ssh = new FakeSshExecutor(containerisedUnitBox());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).isEmpty();
        // The guard fires on the cgroup read, before any cwd is mapped for the container PID.
        assertThat(ssh.commands).doesNotContain(List.of("readlink", "/proc/2500/cwd"));
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
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime)
                .containsExactly(tuple("app", 8080, "process"));
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
                .containsExactly("liveness", "process", "ram", "cpu", "disk",
                        "ram (sudo re-probe)", "disk (sudo re-probe)");
        assertThat(http.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime)
                .containsExactly(tuple("app", 8080, "process"));
    }

    @Test
    void discover_JavaWithAgentAndClasspathJars_NamesFromExecutableJar() {
        // -javaagent:/...newrelic.jar and -cp /...common.jar precede -jar; the app name
        // must come from the EXECUTABLE jar (orders), not the agent/classpath jars.
        FakeSshExecutor ssh = new FakeSshExecutor(agentAndClasspathSpringBoot());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");

        assertThat(springboot.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime)
                .containsExactly(tuple("orders", 8080, "process"));
    }

    @Test
    void discover_GenericJarName_NamesFromDeployDir() {
        // -jar /opt/app.jar → generic "app"; /proc/<pid>/cwd (the deploy dir) names it.
        FakeSshExecutor ssh = new FakeSshExecutor(genericJarDeployDir());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");

        // The deploy dir is now also mapped to a context (spec-055), so assert the
        // name-derivation this test targets on the legacy (appName, port, runtime) fields.
        assertThat(springboot.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime)
                .containsExactly(tuple("birthday-rsvp", 8080, "process"));
    }

    @Test
    void discover_GenericJarAndDir_NamesFromManifestStartClass() {
        // Generic jar AND generic cwd → the fat jar's Start-Class (not the boot-loader
        // Main-Class): package dropped, "Application" stripped, camelCase → kebab.
        FakeSshExecutor ssh = new FakeSshExecutor(genericJarManifest());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");

        // Context mapping (spec-055) now also populates the context fields; this test
        // targets name-derivation, so assert on the legacy scalar fields.
        assertThat(springboot.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port, AppPortItem::runtime)
                .containsExactly(tuple("payment-gateway", 8080, "process"));
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

        // The script is a fixed process-tree CPU-RATE read (spec-057): two /proc/<pid>/stat
        // samples a measured Δt apart, emitting Δticks (fields 14+15) and starttime (field 22)
        // for the client to divide — never mutating, never the lifetime-average ps %cpu.
        String script = cpu.argTokens().stream().map(t -> t.value()).reduce("", (a, b) -> a + "\n" + b);
        assertThat(script).contains("/stat").contains("sleep").contains("starttime").contains("clk_tck");
        assertThat(script).doesNotContain("pcpu"); // the replaced lifetime-average source
        MUTATING_TOKENS.forEach(tok -> assertThat(script).doesNotContain(" " + tok + " "));
    }

    @Test
    void discover_RamProbe_IsPssSumWithLabelledRssFallback() {
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());
        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");
        ProposedAction ram = action(springboot, "ram");

        // Read-only, login-user, fans out over the port like the other native probes.
        assertThat(ram.sudo()).isFalse();
        assertThat(ram.argTokens()).anySatisfy(t -> assertThat(t.value()).isEqualTo("port"));

        String script = scriptOf(ram);
        // Sums PSS from smaps_rollup (the honest RAM figure) — never a naive sum of RSS.
        assertThat(script).contains("smaps_rollup").contains("Pss:");
        // Degrades to VmRSS with an explicit low-confidence label on procfs denial.
        assertThat(script).contains("VmRSS").contains("ram_confidence=low");
        MUTATING_TOKENS.forEach(tok -> assertThat(script).doesNotContain(" " + tok + " "));
    }

    @Test
    void discover_DiskProbe_DuOnAppFolder_RootFsBytes_BindsFolderNotPort() {
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());
        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");
        ProposedAction disk = action(springboot, "disk");

        assertThat(disk.sudo()).isFalse();
        // The bound component is the per-context app-folder (enriched server-side from side-data),
        // NEVER the port — the context path never becomes a caller-supplied value (S9).
        assertThat(disk.argTokens()).anySatisfy(t -> assertThat(t.value()).isEqualTo("app-folder"));
        assertThat(disk.argTokens()).noneSatisfy(t -> assertThat(t.value()).isEqualTo("port"));
        assertThat(disk.paramDefs()).extracting(p -> p.kind())
                .containsExactly(com.iskeru.computeadmin.recipe.model.ParamKind.APP_PORT_LIST);

        String script = scriptOf(disk);
        // du -sbx keeps the walk on the app-folder's own filesystem (root/data-root FS bytes,
        // the double-count rule): under-mounts are -x-excluded and findmnt cross-checked;
        // bounded by timeout/nice/ionice so it never hammers the host.
        assertThat(script).contains("du -sbx").contains("findmnt")
                .contains("timeout").contains("nice").contains("ionice");
        MUTATING_TOKENS.forEach(tok -> assertThat(script).doesNotContain(" " + tok + " "));
    }

    @Test
    void discover_SudoReprobes_AreSudoGatedVariantsOfTheRamAndDiskProbes() {
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());
        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");
        ProposedAction ramSudo = action(springboot, "ram (sudo re-probe)");
        ProposedAction diskSudo = action(springboot, "disk (sudo re-probe)");

        // Decision 6: sudo=true so ParamBinder prepends `sudo -n`; gated like any sudo action.
        assertThat(ramSudo.sudo()).isTrue();
        assertThat(diskSudo.sudo()).isTrue();
        // Same fixed scripts as the no-sudo probes (only the privilege differs).
        assertThat(scriptOf(ramSudo)).isEqualTo(scriptOf(action(springboot, "ram")));
        assertThat(scriptOf(diskSudo)).isEqualTo(scriptOf(action(springboot, "disk")));
    }

    @Test
    void discover_ProcessApp_MapsToOwningContextViaWrapperRule() {
        // cwd /opt/lab/orders/scripts: the "scripts" wrapper hops up to the owning context
        // /opt/lab/orders (spec-055 D2); the item carries the logical script-folder + context.
        FakeSshExecutor ssh = new FakeSshExecutor(contextMappedSpringBoot());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");
        AppPortItem item = springboot.appPortList().get(0);

        assertThat(item.appName()).isEqualTo("orders");
        assertThat(item.scriptFolder()).isEqualTo("/opt/lab/orders/scripts");
        assertThat(item.contextDisplay()).isEqualTo("/opt/lab/orders");
        assertThat(item.contextKey()).isEqualTo("/opt/lab/orders");
        assertThat(item.contextScripts()).containsExactly("orders");
    }

    @Test
    void discover_DockerApp_IsNotMappedToAHostContext() {
        // cgroup-before-cwd guard (spec-055): a container PID's cwd is an overlayfs path,
        // never a host context — the context fields stay null (056 owns docker contexts).
        FakeSshExecutor ssh = new FakeSshExecutor(dockerisedSpringBoot());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");
        AppPortItem item = springboot.appPortList().get(0);

        assertThat(item.runtime()).isEqualTo("docker");
        assertThat(item.contextKey()).isNull();
        assertThat(item.contextDisplay()).isNull();
        assertThat(item.scriptFolder()).isNull();
        // No cwd probe is issued for the container PID (the guard short-circuits it).
        assertThat(ssh.commands).doesNotContain(List.of("readlink", "/proc/1000/cwd"));
    }

    @Test
    void discover_SiblingScriptsOfOneApp_GroupUnderTheSharedContext() {
        // Two listeners whose script-folders collapse to /opt/lab/shop enumerate each other
        // as sibling app-scripts under the one context identity (spec-055 D4).
        FakeSshExecutor ssh = new FakeSshExecutor(twoScriptsOneContext());

        ProposedRecipe springboot = recipe(discoverer.discover(machine(), ssh), "springboot monitor");

        assertThat(springboot.appPortList())
                .allSatisfy(item -> {
                    assertThat(item.contextKey()).isEqualTo("/opt/lab/shop");
                    assertThat(item.contextScripts()).containsExactly("orders", "billing");
                });
    }

    @Test
    void discover_PostgresListener_IsFingerprintedHighConfidenceWithCatalogDataDir() {
        // Decision 5: a postgres listener on the catalog default port 5432 fingerprints against
        // the ServiceCatalog. Two agreeing signals (process AND port) ⇒ confidence high; its
        // context becomes the catalog data dir (no PGDATA override on the environment).
        FakeSshExecutor ssh = new FakeSshExecutor(postgresBox(null));

        ProposedRecipe generic = recipe(discoverer.discover(machine(), ssh), "generic app monitor");
        AppPortItem pg = generic.appPortList().stream()
                .filter(i -> i.appName().equals("postgres")).findFirst().orElseThrow();

        assertThat(pg.port()).isEqualTo(5432);
        assertThat(pg.confidence()).isEqualTo("high");
        assertThat(pg.contextKey()).isEqualTo("/var/lib/postgresql");
    }

    @Test
    void discover_PostgresWithPgdataEnv_VerifiesDataDirFromTheEnvOverride() {
        // Decision 5 "verify": a PGDATA override on /proc/<pid>/environ beats the catalog default
        // before it is trusted, so the mapped context is the real data dir, not the packaged one.
        FakeSshExecutor ssh = new FakeSshExecutor(postgresBox("/data/pg"));

        ProposedRecipe generic = recipe(discoverer.discover(machine(), ssh), "generic app monitor");
        AppPortItem pg = generic.appPortList().stream()
                .filter(i -> i.appName().equals("postgres")).findFirst().orElseThrow();

        assertThat(pg.contextKey()).isEqualTo("/data/pg");
        assertThat(pg.confidence()).isEqualTo("high");
    }

    @Test
    void discover_MariadbOnNonDefaultPort_IsFingerprintedLowConfidence() {
        // A single agreeing signal (process only; the port is not the catalog 3306) ⇒ low.
        FakeSshExecutor ssh = new FakeSshExecutor(mariadbBox());

        ProposedRecipe generic = recipe(discoverer.discover(machine(), ssh), "generic app monitor");
        AppPortItem db = generic.appPortList().stream()
                .filter(i -> i.appName().equals("mariadbd")).findFirst().orElseThrow();

        assertThat(db.port()).isEqualTo(3307);
        assertThat(db.confidence()).isEqualTo("low");
        assertThat(db.contextKey()).isEqualTo("/var/lib/mysql");
    }

    @Test
    void discover_OnlyEverIssuesReadOnlyProbes() {
        FakeSshExecutor ssh = new FakeSshExecutor(mixedBox());

        discoverer.discover(machine(), ssh);

        assertThat(ssh.commands).isNotEmpty();
        assertThat(ssh.commands).allSatisfy(argv -> {
            // Only read-only lenses — the listening sweep (ss/netstat/cat/readlink/unzip/curl)
            // plus the non-listening sweep (systemctl list-units/show, ps -eo, sh -c cron read,
            // ls -a marker check) — never a mutating verb.
            assertThat(argv.get(0)).isIn(
                    "ss", "netstat", "cat", "readlink", "unzip", "curl", "systemctl", "ps", "sh", "ls");
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

    private Function<List<String>, ExecResult> dockerProxyAndNativeApp() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:5432       0.0.0.0:*     users:((\"docker-proxy\",pid=4000,fd=4))",
                "LISTEN 0      128          0.0.0.0:8080       0.0.0.0:*     users:((\"java\",pid=1000,fd=10))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" -> ok("java -jar /opt/orders.jar");
            case "cat /proc/1000/cgroup" -> ok("0::/user.slice/user-1000.slice/session-3.scope");
            case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health" -> ok("{\"status\":\"UP\"}");
            case "readlink /proc/1000/cwd", "readlink -f /proc/1000/cwd" -> ok("/opt/orders");
            default -> notFound();
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

    private Function<List<String>, ExecResult> contextMappedSpringBoot() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:8080       0.0.0.0:*     users:((\"java\",pid=1000,fd=10))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" -> ok("java -jar /opt/orders.jar");
            case "cat /proc/1000/cgroup" -> ok("0::/user.slice/user-1000.slice/session-3.scope");
            case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health" -> ok("{\"status\":\"UP\"}");
            case "readlink /proc/1000/cwd" -> ok("/opt/lab/orders/scripts");
            case "readlink -f /proc/1000/cwd" -> ok("/opt/lab/orders/scripts");
            default -> notFound();
        };
    }

    private Function<List<String>, ExecResult> twoScriptsOneContext() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:8080       0.0.0.0:*     users:((\"java\",pid=1000,fd=10))",
                "LISTEN 0      128          0.0.0.0:8090       0.0.0.0:*     users:((\"java\",pid=1001,fd=11))");
        return argv -> switch (String.join(" ", argv)) {
            case "ss -ltnp" -> ok(ss);
            case "cat /proc/1000/cmdline" -> ok("java -jar /opt/orders.jar");
            case "cat /proc/1001/cmdline" -> ok("java -jar /opt/billing.jar");
            case "cat /proc/1000/cgroup", "cat /proc/1001/cgroup" ->
                    ok("0::/user.slice/user-1000.slice/session-3.scope");
            case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health",
                 "curl -sf -m 2 http://127.0.0.1:8090/actuator/health" -> ok("{\"status\":\"UP\"}");
            // Both scripts collapse to the same owning context /opt/lab/shop.
            case "readlink /proc/1000/cwd", "readlink -f /proc/1000/cwd" -> ok("/opt/lab/shop/scripts");
            case "readlink /proc/1001/cwd", "readlink -f /proc/1001/cwd" -> ok("/opt/lab/shop");
            default -> notFound();
        };
    }

    private Function<List<String>, ExecResult> postgresBox(String pgdataOverride) {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:5432       0.0.0.0:*     users:((\"postgres\",pid=1500,fd=5))");
        String environ = pgdataOverride == null ? null
                : "PATH=/usr/bin\0PGDATA=" + pgdataOverride + "\0LANG=C";
        String dataDir = pgdataOverride == null ? "/var/lib/postgresql" : pgdataOverride;
        return argv -> {
            if (isCronProbe(argv)) {
                return notFound();
            }
            return switch (String.join(" ", argv)) {
                case "ss -ltnp" -> ok(ss);
                case "cat /proc/1500/cmdline" ->
                        ok("/usr/lib/postgresql/16/bin/postgres -D /var/lib/postgresql/16/main");
                case "cat /proc/1500/cgroup" -> ok("0::/system.slice/postgresql.service");
                case "cat /proc/1500/environ" -> environ == null ? notFound() : ok(environ);
                case "readlink -f /var/lib/postgresql" -> ok("/var/lib/postgresql");
                case "readlink -f /data/pg" -> ok("/data/pg");
                default -> notFound();
            };
        };
    }

    private Function<List<String>, ExecResult> mariadbBox() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:3307       0.0.0.0:*     users:((\"mariadbd\",pid=1600,fd=5))");
        return argv -> {
            if (isCronProbe(argv)) {
                return notFound();
            }
            return switch (String.join(" ", argv)) {
                case "ss -ltnp" -> ok(ss);
                case "cat /proc/1600/cmdline" -> ok("/usr/sbin/mariadbd");
                case "cat /proc/1600/cgroup" -> ok("0::/system.slice/mariadb.service");
                case "readlink -f /var/lib/mysql" -> ok("/var/lib/mysql");
                default -> notFound();
            };
        };
    }

    /** True when argv is the fixed {@code sh -c <cron read>} probe (matched structurally). */
    private static boolean isCronProbe(List<String> argv) {
        return argv.size() == 3 && argv.get(0).equals("sh") && argv.get(1).equals("-c")
                && argv.get(2).contains("crontab -l");
    }

    private Function<List<String>, ExecResult> nonListeningBox() {
        String ss = String.join("\n",
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process",
                "LISTEN 0      128          0.0.0.0:8080       0.0.0.0:*     users:((\"java\",pid=1000,fd=10))");
        return argv -> {
            if (isCronProbe(argv)) {
                return ok(String.join("\n",
                        "# m h dom mon dow command",
                        "0 2 * * * /opt/lab/backup/nightly.sh --dest /var/backups"));
            }
            return switch (String.join(" ", argv)) {
                case "ss -ltnp" -> ok(ss);
                case "cat /proc/1000/cmdline" -> ok("java -jar /opt/orders.jar");
                case "cat /proc/1000/cgroup", "cat /proc/2500/cgroup", "cat /proc/3500/cgroup" ->
                        ok("0::/user.slice/user-1000.slice/session-3.scope");
                case "curl -sf -m 2 http://127.0.0.1:8080/actuator/health" -> ok("{\"status\":\"UP\"}");
                case "readlink /proc/1000/cwd", "readlink -f /proc/1000/cwd" -> ok("/opt/orders");
                // systemd: worker.service has a MainPID; nginx.service is a oneshot (MainPID 0).
                case "systemctl list-units --type=service --state=running --no-legend --plain" -> ok(String.join("\n",
                        "worker.service loaded active running Batch worker",
                        "nginx.service  loaded active running Nginx"));
                case "systemctl show -p MainPID --value worker.service" -> ok("2500");
                case "systemctl show -p MainPID --value nginx.service" -> ok("0");
                case "readlink /proc/2500/cwd", "readlink -f /proc/2500/cwd" -> ok("/opt/lab/worker");
                // interpreter scan: the ETL python worker (no socket) + the already-listening java.
                case "ps -eo pid=,args=" -> ok(String.join("\n",
                        "1000 java -jar /opt/orders.jar",
                        "3500 python3 /opt/lab/etl/run.py --daemon",
                        " 900 sshd: /usr/sbin/sshd -D"));
                case "readlink /proc/3500/cwd", "readlink -f /proc/3500/cwd" -> ok("/opt/lab/etl");
                // cron dir resolution.
                case "readlink -f /opt/lab/backup" -> ok("/opt/lab/backup");
                default -> notFound();
            };
        };
    }

    private Function<List<String>, ExecResult> onlyNonListeningBox() {
        return argv -> {
            if (isCronProbe(argv)) {
                return notFound();
            }
            return switch (String.join(" ", argv)) {
                case "systemctl list-units --type=service --state=running --no-legend --plain" ->
                        ok("worker.service loaded active running Batch worker");
                case "systemctl show -p MainPID --value worker.service" -> ok("2500");
                case "cat /proc/2500/cgroup" -> ok("0::/user.slice/user-1000.slice/session-3.scope");
                case "readlink /proc/2500/cwd", "readlink -f /proc/2500/cwd" -> ok("/opt/lab/worker");
                default -> notFound(); // no ss, no ps, no cron
            };
        };
    }

    private Function<List<String>, ExecResult> containerisedUnitBox() {
        return argv -> {
            if (isCronProbe(argv)) {
                return notFound();
            }
            return switch (String.join(" ", argv)) {
                case "systemctl list-units --type=service --state=running --no-legend --plain" ->
                        ok("worker.service loaded active running Containerised worker");
                case "systemctl show -p MainPID --value worker.service" -> ok("2500");
                // The unit's MainPID lives in a docker cgroup → Decision 2 drops it.
                case "cat /proc/2500/cgroup" -> ok("0::/docker/worker-ctr");
                default -> notFound();
            };
        };
    }

    private static ProposedRecipe recipe(List<ProposedRecipe> recipes, String name) {
        return recipes.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static ProposedAction action(ProposedRecipe recipe, String name) {
        return recipe.actions().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    /** The flattened script text of a probe action (its argTokens joined), for content asserts. */
    private static String scriptOf(ProposedAction action) {
        return action.argTokens().stream().map(t -> t.value()).reduce("", (a, b) -> a + "\n" + b);
    }

    private static Machine machine() {
        Machine machine = new Machine();
        machine.setHost("host");
        machine.setPort(22);
        machine.setLoginUser("deploy");
        return machine;
    }
}
