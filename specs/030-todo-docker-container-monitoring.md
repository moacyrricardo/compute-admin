# 030 — Docker container monitoring

> **Concern** — options open, not a decision. Raised after the fleet Monitor (spec 029)
> discovered **none** of a box's running containers: a `docker ps` there showed a
> FastAPI api (published `0.0.0.0:8010->8000`), a Celery worker (no published port),
> `postgres`, `redis`, `langfuse`, and `langfuse-db` — and not one surfaced as a
> monitored app.

## Problem

App-monitor discovery (spec 025) finds apps from the **host's** `ss -ltnp` → PID →
`/proc/<pid>/cmdline`. For containers that chain breaks structurally:

- A **published port** (`0.0.0.0:8010->8000`) is owned on the host by `docker-proxy`
  — or, with iptables-based publishing, has **no host listener at all** (DNAT straight
  to the container netns). So the host never sees `uvicorn`/`java`; classification and
  the `/proc` metrics read both miss. `docker-proxy` is usually root-owned → often not
  even visible to the login user's `ss` (the S5 gap).
- **Portless containers** (a Celery worker; `langfuse-db` *exposing* `5432` but not
  *publishing* it) have no host listener → a port-based discoverer is structurally blind.
- The spec-022 container "double-detection" only fires when the *listener's* PID
  resolves to a container cgroup, which never happens behind `docker-proxy`.

So containerized apps — likely the majority on a real box — are invisible to
monitoring today. `DockerDiscoverer` already lists containers for **ops** (start/stop/
logs) but there is no **health/metrics** lens. This concern explores adding one.

## Hypotheses / Options

### A. Probe source
- **A1 — Docker-native.** `docker ps` (enumerate) + `docker stats --no-stream` (cpu,
  mem usage/limit, mem%, one call covering *all* containers incl. portless workers/
  datastores) + `docker inspect` (health, restart count) + optional published-port or
  `docker exec` liveness. *Pros:* sees everything; mem-of-limit is the right metric;
  one `stats` call per machine. *Cons:* needs docker access (docker group / sudo) —
  the same assumption `DockerDiscoverer` already makes.
- **A2 — Published-port → container map.** Detect `docker-proxy` on a host port, map
  port→container via `docker ps`, then probe. *Pros:* reuses the port path. *Cons:*
  misses portless containers; brittle; solves nothing for metrics.
- **A3 — cgroup host-walk.** Read container cgroups from the host `/sys/fs/cgroup`
  without docker. *Pros:* no docker dependency. *Cons:* complex; rootless/permission
  issues; no image/health metadata.

### B. Classifying a container
By image / command: `uvicorn`/`gunicorn`→fastapi · `java`/`-jar`→springboot ·
`celery`/`rq`→worker (metrics-only, no port) · `postgres`/`redis`/`mysql`/`mongo`→
datastore (metrics + a datastore health probe: `pg_isready` / `redis-cli ping` via
`docker exec`) · else generic container. Open: does the app-name come from the
container name, the image, or a compose service label?

### C. Doubt #1 — should docker monitor-discovery be *gated* behind `docker ps` being allowed? (a recipe-depends-on-recipe model?)
*Options, no decision:*
- **C1 — Probe-and-propose, no gate (the current discoverer contract).** The discoverer
  just runs read-only `docker ps` during discovery; if docker is absent/not permitted it
  proposes nothing. No new concept (spec-006: discoverers probe read-only, never mutate,
  propose only). The read-only probe *is* the implicit gate.
- **C2 — Explicit capability prerequisite (recipe dependency).** Docker monitor-discovery
  only runs after an approved "docker available" recipe/action — a first-class **recipe
  dependency** (recipe A requires recipe B approved). *Pros:* explicit operator opt-in to
  touching docker; audit trail. *Cons:* a new dependency primitive (recipes are
  independent today); more ceremony.
- **C3 — Discovery-time capability check, surfaced.** Probe `docker ps` read-only; on a
  permission failure, surface a "docker present but not permitted — grant the login user
  docker access" hint instead of silently proposing nothing. Middle ground: no dependency
  primitive, but the gate is visible.
- **C4 — Two-phase discovery.** A cheap "is docker usable?" probe proposes a single
  `monitor docker` / `docker available` recipe; approving it unlocks the fuller
  per-container discovery. A soft C2 without a general dependency mechanism.
- *Cross-cutting:* is `docker ps` output (image + container names) itself sensitive over
  MCP (S9-adjacent infra exposure)? Sub-options: expose freely / filter to name+status
  for MCP / UI-only.

### D. Doubt #2 — a Spring Boot running in Docker: how should monitoring look?
*Options, no decision:*
- **D1 — Container lens only.** `docker stats` (cpu, **mem% of limit**) + container
  health (`docker inspect .State.Health`) + restart count + `docker logs`. Uniform with
  every other container; ignores actuator; loses app-level/JVM detail.
- **D2 — App lens only.** Probe the **published port's actuator**
  (`curl 127.0.0.1:<publishedPort>/actuator/health`, `/metrics`) and treat it like a
  host springboot app. Loses container truth (mem-of-limit, restarts); needs a published
  port (portless → invisible); mem becomes JVM heap, not container RSS.
- **D3 — Unified / dual lens (spec-022 "double-detection" realized).** One card fuses
  **both**: container metrics (docker stats mem%/cpu/limit, restarts, health) **and** app
  health (actuator via the published port or `docker exec`), keyed by the same app-name/
  container. *Open sub-questions:* which is the **headline** metric (container mem-of-limit
  vs JVM heap%)? how does app-name reconcile — container name vs `-Dspring.application.name`
  vs the spec-025 jar/name-resolution?
- **D4 — Classify-then-choose.** If actuator answers → D3 (fused); else → D1
  (container-only). Adaptive.
- *Precedence/dedup:* a containerized springboot may also be (mis)seen by the host `ss`
  path (as `docker-proxy`, failing). The container lens should be **authoritative** for
  containerized apps — one app = one card (spec-022 app-name link, `runtime=docker` wins).

## Open Questions

1. **Gating (doubt #1):** which of C1–C4 — and does compute-admin want a general
   **recipe-dependency** primitive, or is the discoverer's own read-only `docker ps` probe
   a sufficient implicit gate? Is `docker ps` output MCP-sensitive (S9)?
2. **Spring-Boot-in-Docker (doubt #2):** which of D1–D4 — and if unified (D3), what is the
   headline metric and how does app-name reconcile across the container name,
   `spring.application.name`, and the spec-025 name-resolution?
3. **Metric semantics:** mem **% of container limit** (docker stats) vs % of host — and how
   that renders on the 029 fleet card's existing "mem % of host" bar (relabel? per-source?).
4. **Permissions:** login user in the `docker` group vs `sudo docker` — and how that
   interacts with S5 (login-user-only) and the sudo posture (S4/S5).
5. **Relationship to `DockerDiscoverer`:** extend it with a monitor lens vs a parallel
   `DockerMonitorDiscoverer`; how the ops (026) and monitor lenses unify on one card by
   container name.
6. **Datastore health:** is a `docker exec pg_isready` / `redis-cli ping` in scope, or
   metrics-only for v1?

Graduates into a spec (or a set) once #1 and #2 are decided.
