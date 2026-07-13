# 033 — Docker container discovery

> **Status: done.** Branch `moacyrricardo/spec-033-docker-container-discovery`, stacked
> on `moacyrricardo/spec-032-monitoring-axes-foundations` (merge after spec-032). No
> Flyway migration and no new `RecipeType` — see "Divergences" below.

> Resolves [concern 030](./030-todo-docker-container-monitoring.md) (docker-native
> monitoring). Builds on the consumer contract in
> [032](./032-todo-monitoring-axes-foundations.md), the discovery idempotency of
> [021](./021-done-discovery-idempotency.md), and the app-monitor routing of
> [025](./025-done-app-monitor-recipes.md). The **per-family enablement gate** that
> decides *whether* docker discovery may probe at all is
> [035](./035-todo-discovery-enablement-and-ux.md); this spec assumes it is enabled.

## Context

The fleet Monitor finds apps from the **host's** `ss -ltnp` → PID → `/proc/<pid>/cmdline`
(spec-025). For containers that chain breaks structurally (concern 030): a published
port is owned by `docker-proxy` (or DNAT'd with no host listener at all), portless
workers and datastores have no host socket, and the S5 login-user often can't see the
root-owned proxy PID. So the majority of a real box — the compose stacks — is invisible.

Docker already labels everything it runs. That is the way in.

## Decision

1. **A docker app = a compose project.** Group containers by the
   `com.docker.compose.project` label; the project name **is** the `appName` (spec-022
   convention). `com.docker.compose.service` gives the per-service role (web/worker/db);
   `com.docker.compose.project.config_files` locates the compose file (best-effort). No
   port is required — portless workers and datastores are monitored because they belong
   to a project, not because a host socket sees them.

2. **A new `DockerComposeDiscoverer`** (`discovery/service`, implements the existing
   `RecipeDiscoverer` port; sibling to `DockerDiscoverer`/`SystemdDiscoverer`) enumerates
   containers via `docker ps` and proposes **MONITOR** recipes only, `PENDING_APPROVAL`,
   never auto-approved — the gate is untouched. It emits the consumer labels 032 defined:
   `source=DOCKER`, `role`, `dedication`, `owner`/`usedBy`.

3. **Classification (concern 030 option B) is by image + compose service.** Known
   datastore images (`postgres|mysql|mariadb|mongo|redis|…`) or a `db`-shaped service name
   → `role=DATABASE`. A compose project with a web/app service → an **app**; its own
   datastore service is **DEDICATED** to that project (`owner=<project>`). A datastore
   **not** in any project (a `docker run` redis) → **SHARED** (`usedBy` inferred from
   which projects reference it, else left as standalone). Everything else docker →
   the **`DOCKER` bucket** (032).

4. **Metrics come from docker, not `/proc`.** RAM/CPU per container from
   `docker stats --no-stream` (cgroup); disk from **writable layer** (`docker ps -s`) **+
   named volumes** (`docker system df -v`), summed per project, as a % of the docker
   data-root filesystem — the disk axis 032 left `null` for native apps. Aggregated per
   compose project into the consumer's `ram/cpu/disk`.

5. **Springboot-in-docker is shown once (concern 030 doubt (b), decided).** When a
   container both classifies as a framework app (springboot/fastapi) **and** carries
   compose labels, it is a single consumer, `source=DOCKER`, framework badge preserved —
   the container lens and the app lens are **unified on one card**, not two. The
   native-vs-docker dedup keys on `appName`: a project name and a native spec-025
   `app-name` that collide resolve to one consumer, docker-sourced.

## Implementation

All new code cites `spec-033`.

- **`DockerComposeDiscoverer`** — probes (read-only, login-user or `sudo` per the S5
  posture): `docker ps --format '{{json .}}' --filter label=com.docker.compose.project`
  → group by project → propose one MONITOR recipe per project with per-service checks
  (a `stats` check for RAM/CPU, a `disk` check via `system df -v`, and a framework
  liveness check where the service exposes one). Container name / cgroup recovery from
  spec-025 is reused where a listener *does* resolve.
- **Idempotency (021).** Reconcile by `(machine, type=MONITOR, name=<project>)`; refresh
  unapproved proposals in place, diff on APPROVED-differs, uniqueness guard — no
  duplicate cards when re-run.
- **Datastore classification** table lives in `discovery/service` (image-prefix set +
  default ports), reused by both the compose path and standalone containers. Emits
  `role=DATABASE` + `dedication`.
- **Metrics parsing** (client-side, mirroring spec-023/025's degrade-to-raw): parse
  `docker stats` MEM/CPU %, `docker system df -v` volume sizes; unparⁿsable → raw text
  fallback. Feeds the 032 consumer axes.
- **No new `RecipeType`.** These are `MONITOR` recipes (spec-022), display-only; the gate
  is unchanged, `mcp/` is untouched, `GateArchTest` stays green.
- **Enablement.** The discoverer runs only when docker discovery is enabled for the
  machine (035). Absent that, it is a no-op (proposes nothing) — it never probes docker
  speculatively.

## Known Gaps

- **`docker ps -s` / `system df -v` are slow.** Disk sizing is heavier than `stats`; it
  polls on a lazier cadence than RAM/CPU (disk moves slowly). Documented, tuned in 034's
  cadence handling.
- **Shared-datastore ownership is inferred, not authoritative.** A standalone redis used
  by two apps has no machine-readable owner; `usedBy` is best-effort (network/link
  heuristics) and may be empty → shown as `SHARED` with `usedBy: —`.
- **Compose file may be unreachable** (`config_files` points at a deploy dir that's gone
  or root-only). The *grouping* is always reliable from labels; showing the file is
  best-effort (034 degrades gracefully).
- **Image-prefix classification is a denylist-ish heuristic** — an unusual datastore image
  lands in the `DOCKER` bucket until its prefix is added.
- **Rootless / non-default docker** (podman, custom socket) is out of scope for v1;
  detection assumes the standard docker CLI + socket.
- **Disk denominator** is the docker data-root filesystem, not `/` — stated so the % is
  interpreted correctly.

## Divergences (as built)

Faithful to the decisions; the deltas below are implementation choices forced by the
"no schema, no new `RecipeType`, don't disturb spec-032" constraints of this stacked PR.

- **Interim enablement flag.** The per-machine enablement gate (spec-035) is not built
  yet, so docker discovery is guarded behind a single boolean property
  `ca.discovery.docker.enabled` (default **false**). While disabled the
  `DockerComposeDiscoverer` is a strict no-op — it proposes nothing and **never probes
  docker** (asserted: zero commands sent). spec-035 supersedes this flag with the
  per-machine UI model.
- **No schema, no new type.** The classified consumers are persisted as JSON on the
  recipe's existing un-audited `app_port_list` column as `{"dockerConsumers":[…]}` (told
  apart from the native `[{appName,port}]` array by being an object). No Flyway migration
  was added; the recipes are `MONITOR` (no new `RecipeType`); the gate/`GateArchTest` are
  untouched.
- **Consumer granularity.** A project's own datastore is emitted as its **own**
  `DEDICATED` `DATABASE` consumer (`owner=<project>`), and the project's `APP` consumer
  lists its **app** (non-datastore) containers as `services` — chosen to honour
  "DEDICATED, owner=project" literally, since `ConsumerServiceView` (spec-032) carries no
  per-service dedication field. A standalone datastore → `SHARED` (`usedBy` best-effort,
  left empty in v1); the remainder → one `DOCKER` bucket consumer (empty `services`, per
  spec-032 §5).
- **Enumeration probe.** Uses the unfiltered `docker ps --format '{{json .}}'` (rather
  than the label-filtered variant in the Implementation sketch) so standalone datastores
  and the bucket remainder are visible in one read.
- **Checks are fixed, project-agnostic reads.** Each project recipe carries the same
  three param-free read-only checks (`docker stats --no-stream`, `docker ps -s`,
  `docker system df -v`); the client attributes their output per container by name using
  the consumer's `services`. Per-service HTTP liveness fan-out is deferred.
- **Axes + fleet UI are spec-034.** Server assembly leaves `ram`/`cpu`/`disk` `null`; the
  client parsers (`parseDockerStats`/`parseDockerPs`) feed those axes once spec-034
  renders the consumer cards. For spec-033 the docker checks land as host-panel `MONITOR`
  actions and `app.js` only makes an approved docker monitor legible (degrade-to-raw),
  with no new headline bars.
