# Demo fake fleet — data & the `demo` profile

The monitor has **no stored metrics**: every axis value comes from a live poll
(`POST /runs` → SSH → parse client-side). So a deterministic demo fleet requires a
**canned SSH layer** — we cannot use the real MINA executor (no real hosts) and
`localssh` would only surface the real local box. This file is the contract for that
layer; keep it in sync with the discoverers/parsers (the "source" column).

## The fleet we depict

| Machine | Apps (>1) | Shared datastore | Notes |
|---|---|---|---|
| **web-prod-1** (`web@10.10.0.11`) | `checkout-api` (springboot, :8080), `web-frontend` (generic, :3000) | **docker** `postgres` (standalone container, used by both apps → `Dedication.SHARED`) | added **live** in GIF 1 |
| **api-prod-2** (`api@10.10.0.12`) | `orders-api` (springboot, :8080), `billing-worker` (generic, :9000) | **native** `postgres` (host process on :5432, used by both → `SHARED`) | **pre-seeded** so GIF 3 shows two machines |

Both datastores are **SHARED** (two using apps each) so the Monitor **Databases lens**
shows a populated *Shared* band; one is DOCKER-sourced, one NATIVE-sourced, which is the
contrast the demo is about. Each machine also shows its host RAM/CPU/disk and — via
spec-041 — an **other/system** segment.

## The `demo` Spring profile (to implement)

Activated with `-Dspring-boot.run.profiles=demo`. Two beans, both `@Profile("demo")`:

### 1. `CannedSshExecutor` — `@Primary @Profile("demo") implements SshExecutor`

Replaces the MINA executor. Returns scripted stdout/exit per **(host, argv)**, matching
the [contract table](#commandoutput-contract) below. Sketch (validate argv against the
real discoverers before trusting it):

```java
@Component @Primary @Profile("demo")
class CannedSshExecutor implements SshExecutor {
  // host → (command-substring → canned stdout). Fall through to "" + exit 0.
  private final Map<String, List<Map.Entry<String,String>>> script = DemoFleet.script();
  @Override public ExecResult exec(SshTarget target, List<String> argv, ...) {
    String cmd = String.join(" ", argv);
    for (var e : script.getOrDefault(target.host(), List.of()))
      if (cmd.contains(e.getKey())) return ExecResult.ok(e.getValue());
    return ExecResult.ok("");   // unknown probe → empty, never a hard failure
  }
}
```
`DemoFleet` holds the per-host, per-command strings from the table. Match on a stable
**command substring** (e.g. `"docker ps"`), not exact argv, so small flag changes don't
silently break the demo.

### 2. `DemoSeeder` — `@Profile("demo") ApplicationRunner`

On boot: create the demo user (`demo@example.com` / a fixed password — document it here
and in the run steps), and **pre-register `api-prod-2`** (so GIF 3 has two machines).
Do **not** pre-register `web-prod-1` — it is added on camera in GIF 1. Seed nothing that
the gate would otherwise own: discovered recipes still land `PENDING_APPROVAL` and are
approved on camera in GIF 2 (keeps the demo honest about the gate).

> Login: **`demo@example.com`** / **`demo-pass`** (change here + steps.md together).

## Command→output contract

Each row: a probe some discoverer or monitor poll issues, the canned stdout that yields
the intended consumer/metric, and the **source** that defines the format (validate on
first run — argv and formats are authoritative in the code, not here).

| Probe (command substring) | Canned output (per host, shaped for the fleet above) | Source (format authority) |
|---|---|---|
| connectivity / auto-tag: `uname`, `cat /etc/os-release` | `Linux` + a Ubuntu os-release block | `discovery` auto-tag probe (spec-018) |
| app listeners: `ss -ltnp` | web-prod-1: rows for :8080 (java), :3000 (node), :5432→**absent** (docker-proxy hides it); api-prod-2: :8080 (java), :9000 (python), :5432 (postgres) | `AppMonitorDiscoverer` + `ss`→PID→cmdline classifier (spec-025) |
| pid identity: `cat /proc/<pid>/cgroup`, `/proc/<pid>/cmdline` | cmdlines that classify `checkout-api`/`orders-api`→springboot, `web-frontend`/`billing-worker`→generic; postgres on api-prod-2→database | spec-025 classifier |
| docker present: `command -v docker` | web-prod-1: `/usr/bin/docker`; api-prod-2: **empty** (exit ≠0, no docker) | `Probes.commandExists`, `DockerComposeDiscoverer` (spec-033) |
| docker inventory: `docker ps --format '{{json .}}'` | web-prod-1: one line for the `postgres` container (image `postgres:16`, **no** `com.docker.compose.project` label → standalone datastore → SHARED); api-prod-2: n/a | `DockerComposeDiscoverer.containers` / `parseLabels` (spec-033) |
| host RAM: `free -m` | `Mem:` line with total/used so RAM% + spec-041 other/system land (e.g. web-prod-1 `16000` total / `9000` used) | `parseMem` (app.js) |
| host CPU: `top -bn1` | a `%Cpu(s): … id` line (e.g. `82.0 id` → 18% used) | host-CPU parser (spec-041, app.js) |
| host disk: `df -h` | a `/` row with size + Use% (e.g. `40G … 62%`) | `parseDfTotal` + spec-041 disk-used (app.js) |
| host cores: `nproc` | `4` | `pollHostCores` (spec-037, app.js) |
| app RAM/liveness: `ps`/`/proc` VmRSS per app-name | per-app RSS so each app shows a plausible RAM% (checkout-api ~1200MB, etc.) | `parseRssMb` / `applyConsumerReading` (spec-034/039) |
| app CPU: `ps -o pcpu` (process tree) | per-app %CPU so the native CPU axis fills (spec-039) | `parseAppCpu` (spec-039, app.js) |
| docker metrics: `docker stats --no-stream --format '{{json .}}'`, `docker ps -s`, `docker system df -v` | web-prod-1 postgres container CPU%/mem/disk so the docker datastore axes fill (spec-037) | `parseDockerStats`/`parseDockerPs`/`parseDockerVolumes` (spec-033/037, app.js) |

> **Native shared DB (api-prod-2):** produced by the native database discoverer + the
> `ss` :5432 postgres listener classifying as `role=DATABASE`, `SHARED`. Confirm which
> discoverer emits the native datastore consumer when implementing (the docker path is
> `DockerComposeDiscoverer`; the native path is the `ss`/DB classifier).

## Alternative (no canned layer): two throwaway sshd containers

The `spec-003` recipe boots a `linuxserver/openssh-server` with the app key. You *could*
run two, install fake apps + a postgres (native in one, docker-in-docker in the other),
and point real MINA at them. **Rejected as the default**: much heavier to set up
reproducibly, dind is awkward, and it drifts. The canned layer is deterministic and
fast. Keep this note only as a fallback if the canned contract becomes unmaintainable.
