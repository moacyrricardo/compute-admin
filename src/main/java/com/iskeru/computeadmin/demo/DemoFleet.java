package com.iskeru.computeadmin.demo;

import com.iskeru.computeadmin.ssh.ExecResult;

import java.util.List;
import java.util.Map;

/**
 * The canned fake-fleet data + response logic behind {@link CannedSshExecutor} (the
 * {@code demo} profile). Given a target host and the discrete argv a discoverer or a
 * monitor poll issues, it returns the scripted stdout that yields the intended
 * consumer/metric — matching the command→output contract in {@code demo/fake-fleet.md}.
 *
 * <p>Matching is on stable command <strong>substrings</strong> (e.g. {@code "docker ps"},
 * {@code "free -m"}) and, for the fan-out app probes, on the {@code sh -c <script> sh <port>}
 * argv shape plus a marker inside the script body ({@code VmRSS} → process probe,
 * {@code pcpu=} → cpu probe, {@code /actuator/health} → liveness). Any unrecognised probe
 * returns empty stdout, exit 0 — never a hard failure (fake-fleet.md).
 *
 * <p>The fleet (fake-fleet.md):
 * <ul>
 *   <li><b>web-prod-1</b> ({@code web@10.10.0.11}): {@code checkout-api} (springboot, :8080),
 *       {@code web-frontend} (generic, :3000), and a <b>docker</b> standalone {@code postgres}
 *       container (SHARED datastore). Its :5432 is hidden from {@code ss} by docker-proxy.</li>
 *   <li><b>api-prod-2</b> ({@code api@10.10.0.12}): {@code orders-api} (springboot, :8080),
 *       {@code billing-worker} (generic, :9000), and a <b>native</b> {@code postgres} process
 *       (:5432). No docker on this host.</li>
 * </ul>
 *
 * <p>Pure, stateless helper — no Spring, no suffix. Not gated itself; only its sole caller
 * {@link CannedSshExecutor} is {@code @Profile("demo")}.
 */
final class DemoFleet {

    static final String WEB = "10.10.0.11";   // web-prod-1
    static final String API = "10.10.0.12";   // api-prod-2

    private DemoFleet() {
    }

    /** {@code /proc/<pid>/cmdline} per listener PID — drives the spec-025 classifier. */
    private static final Map<String, String> CMDLINE = Map.of(
            "1001", "java -Dspring.application.name=checkout-api -jar /opt/checkout-api.jar",
            "1002", "node /srv/web-frontend/server.js",
            "2001", "java -Dspring.application.name=orders-api -jar /opt/orders-api.jar",
            "2002", "python3 /srv/billing-worker/worker.py",
            "2003", "/usr/lib/postgresql/16/bin/postgres -D /var/lib/postgresql/16/main");

    /** (host:port) → app process facts the monitor poll reads (rssKb, cpu%, pid, comm). */
    private record App(String pid, int rssKb, double cpu, String comm) {
    }

    private static final Map<String, App> APPS = Map.of(
            WEB + ":8080", new App("1001", 1_228_800, 45.0, "java"),          // checkout-api ~1200 MB
            WEB + ":3000", new App("1002", 409_600, 20.0, "web-frontend"),    // web-frontend ~400 MB
            API + ":8080", new App("2001", 1_536_000, 60.0, "java"),          // orders-api ~1500 MB
            API + ":9000", new App("2002", 307_200, 15.0, "billing-worker"),  // billing-worker ~300 MB
            API + ":5432", new App("2003", 819_200, 10.0, "postgres"));       // native postgres ~800 MB

    /** The single entry point: the canned result for {@code argv} on {@code host}. */
    static ExecResult exec(String host, List<String> argv) {
        String cmd = String.join(" ", argv);

        // 1. Fan-out app probe: `sh -c <script> sh <port>` (spec-025/032 templates).
        if (argv.size() >= 5 && "sh".equals(argv.get(0)) && "-c".equals(argv.get(1))) {
            return appProbe(host, argv.get(2), argv.get(argv.size() - 1));
        }

        // 2. `command -v <binary>` — presence test (Probes.commandExists: non-blank ⇒ present).
        if (argv.size() >= 3 && "command".equals(argv.get(0)) && "-v".equals(argv.get(1))) {
            String binary = argv.get(2);
            if ("docker".equals(binary) && WEB.equals(host)) {
                return ok("/usr/bin/docker\n");
            }
            return ok("");   // everything else absent on both hosts (empty ⇒ not present)
        }

        // 3. Discovery-time direct actuator probe (respondsToActuator, spec-025) — plain curl.
        if (cmd.contains("/actuator/health")) {
            return ok(HEALTH_JSON);
        }

        // 4. Host / docker / facts probes, matched by substring.
        if (cmd.contains("ss -ltnp")) {
            return ok(ssListeners(host));
        }
        if (cmd.contains("cat /etc/os-release")) {
            return ok(OS_RELEASE);
        }
        if (cmd.startsWith("cat /proc/")) {
            String pid = procPid(argv.get(1));
            if (cmd.endsWith("/cmdline")) {
                return ok(CMDLINE.getOrDefault(pid, ""));
            }
            if (cmd.endsWith("/cgroup")) {
                return ok("0::/\n");   // no docker/.service segment ⇒ Runtime.PROCESS ⇒ source NATIVE
            }
            return ok("");
        }
        if (cmd.startsWith("cat /sys/class/dmi")) {
            return ok("");             // no cloud DMI signal
        }
        if (cmd.contains("free -m")) {
            return ok(WEB.equals(host)
                    ? freeMem(16000, 9000, 2048, 256)
                    : freeMem(8000, 3000, 2048, 128));
        }
        if (cmd.contains("top -bn1")) {
            return ok(WEB.equals(host) ? topLine(82.0) : topLine(60.0));   // 18% / 40% used
        }
        if (cmd.contains("df -h")) {
            return ok(WEB.equals(host) ? dfRoot("40G", "24G", "16G", 62)
                    : dfRoot("100G", "45G", "55G", 45));
        }
        if (cmd.equals("nproc") || cmd.contains("nproc")) {
            return ok(WEB.equals(host) ? "4\n" : "8\n");
        }
        // Docker (web-prod-1 only; api-prod-2 has no docker binary so these never fire there).
        if (cmd.contains("docker stats")) {
            return ok(DOCKER_STATS);
        }
        if (cmd.contains("docker system df")) {
            return ok(DOCKER_VOLUMES);
        }
        if (cmd.contains("docker ps -s")) {
            return ok(DOCKER_PS_SIZE);
        }
        if (cmd.contains("{{.Names}}")) {
            return ok("postgres\n");
        }
        if (cmd.contains("docker ps")) {
            return ok(DOCKER_PS_JSON);
        }

        // 5. Anything else (e.g. the connectivity `true` probe) → empty, exit 0.
        return ok("");
    }

    // --- app probes ---------------------------------------------------------

    private static ExecResult appProbe(String host, String script, String port) {
        App app = APPS.get(host + ":" + port);
        if (script.contains("VmRSS")) {                 // spec-025 process probe → RSS/liveness
            if (app == null) {
                return ok("no listener on port " + port + "\n");
            }
            return ok("## pid " + app.pid() + "\n"
                    + CMDLINE.getOrDefault(app.pid(), app.comm()) + "\n"
                    + "VmRSS:\t" + app.rssKb() + " kB\n"
                    + "Threads:\t25\n"
                    + "utime=1200 stime=300 starttime=9000\n"
                    + "fds=64\n");
        }
        if (script.contains("pcpu=")) {                 // spec-032 process-tree CPU probe
            if (app == null) {
                return ok("no listener on port " + port + "\n");
            }
            return ok("## pid " + app.pid() + "\n"
                    + app.pid() + " 1 " + app.cpu() + " " + app.comm() + "\n");
        }
        if (script.contains("/actuator/health")) {
            return ok(HEALTH_JSON);
        }
        if (script.contains("/actuator/metrics")) {
            return ok("{\"names\":[\"jvm.memory.used\",\"http.server.requests\"]}\n");
        }
        if (script.contains("/actuator/")) {
            return ok("{}\n");
        }
        if (script.contains("/openapi.json")) {
            return ok("{\"openapi\":\"3.0.0\"}\n");
        }
        return ok("OK\n");                               // generic HTTP liveness (GET /)
    }

    // --- ss listeners -------------------------------------------------------

    /**
     * {@code ss -ltnp} output the spec-025 classifier parses (needs {@code LISTEN} +
     * {@code users:((...))}, a {@code Local Address:Port}, and {@code ("<comm>",pid=N}).
     * web-prod-1: :8080 java + :3000 web-frontend (its :5432 is hidden by docker-proxy).
     * api-prod-2: :8080 java + :9000 billing-worker + :5432 postgres (native).
     */
    private static String ssListeners(String host) {
        StringBuilder sb = new StringBuilder(
                "State  Recv-Q Send-Q Local Address:Port  Peer Address:Port Process\n");
        if (WEB.equals(host)) {
            sb.append(ssRow(8080, "java", "1001"));
            sb.append(ssRow(3000, "web-frontend", "1002"));
        } else if (API.equals(host)) {
            sb.append(ssRow(8080, "java", "2001"));
            sb.append(ssRow(9000, "billing-worker", "2002"));
            sb.append(ssRow(5432, "postgres", "2003"));
        }
        return sb.toString();
    }

    private static String ssRow(int port, String comm, String pid) {
        return String.format("LISTEN 0      128    0.0.0.0:%d        0.0.0.0:*         "
                + "users:((\"%s\",pid=%s,fd=10))%n", port, comm, pid);
    }

    // --- host vitals --------------------------------------------------------

    private static String freeMem(int total, int used, int swapTotal, int swapUsed) {
        int free = total - used;
        return "              total        used        free      shared  buff/cache   available\n"
                + String.format("Mem:      %10d %11d %11d %11d %11d %11d%n",
                        total, used, free, 200, 4000, total - used + 2000)
                + String.format("Swap:     %10d %11d %11d%n", swapTotal, swapUsed, swapTotal - swapUsed);
    }

    private static String topLine(double idle) {
        double used = 100.0 - idle;
        return "top - 12:00:00 up 10 days,  3:21,  1 user,  load average: 0.42, 0.51, 0.48\n"
                + "Tasks: 210 total,   1 running, 209 sleeping\n"
                + String.format("%%Cpu(s): %.1f us,  3.0 sy,  0.0 ni, %.1f id,  0.0 wa%n", used - 3.0, idle);
    }

    private static String dfRoot(String size, String used, String avail, int usePct) {
        return "Filesystem      Size  Used Avail Use% Mounted on\n"
                + "tmpfs           1.6G  2.0M  1.6G   1% /run\n"
                + String.format("/dev/sda1       %4s  %4s  %4s  %2d%% /%n", size, used, avail, usePct);
    }

    // --- static blobs -------------------------------------------------------

    private static final String HEALTH_JSON = "{\"status\":\"UP\"}\n";

    private static final String OS_RELEASE = String.join("\n",
            "NAME=\"Ubuntu\"",
            "VERSION=\"22.04.3 LTS (Jammy Jellyfish)\"",
            "ID=ubuntu",
            "ID_LIKE=debian",
            "VERSION_ID=\"22.04\"") + "\n";

    private static final String DOCKER_PS_JSON =
            "{\"Names\":\"postgres\",\"Image\":\"postgres:16\",\"Labels\":\"\"}\n";

    private static final String DOCKER_STATS =
            "{\"Name\":\"postgres\",\"CPUPerc\":\"3.50%\",\"MemPerc\":\"12.00%\","
            + "\"MemUsage\":\"1.9GiB / 16GiB\"}\n";

    private static final String DOCKER_PS_SIZE =
            "{\"Names\":\"postgres\",\"Size\":\"120MB (virtual 400MB)\"}\n";

    private static final String DOCKER_VOLUMES = String.join("\n",
            "Local Volumes space usage:",
            "VOLUME NAME                     LINKS     SIZE",
            "postgres_data                   1         2.1GB") + "\n";

    // --- helpers ------------------------------------------------------------

    /** Extracts the {@code <pid>} from a {@code /proc/<pid>/...} path. */
    private static String procPid(String path) {
        String rest = path.substring("/proc/".length());
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    private static ExecResult ok(String stdout) {
        return new ExecResult(0, stdout, "");
    }
}
