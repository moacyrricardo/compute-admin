# 033 — Docker container discovery

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
