# 025 — App-monitor recipes (springboot / fastapi / generic), discovery-routed

> **Status:** done. Branch `moacyrricardo/monitoring-021-025` (PR #35). Linear
> BLOCKED — commits use `spec-025`. Graduated from concern
> [020](./020-todo-machine-monitoring.md). **Depends on
> [022](./022-done-monitoring-foundations.md)** (`RecipeType.MONITOR`, the
> `APP_PORT_LIST` param + fan-out run mode, the `appName`/`runtime` convention and
> the double-detection link) and transitively **[021](./021-done-discovery-idempotency.md)**.

## Implementation notes (as-built)

Implemented to the decision. Deviations / concretions worth recording:

- **Probes are fixed `sh -c` script templates, not bare discrete tokens.** The
  spec's endpoint-probe example (`[LIT "curl", … , LIT "http://127.0.0.1:", PARAM
  "port", LIT "/actuator/health"]`) is illustrative ("E.g."): the 004 token model
  cannot embed a validated `PARAM` *inside* a single URL argv element, and three
  separate `http://127.0.0.1:`/`<port>`/`/actuator/health` argv elements are not a
  working `curl`. So every probe (endpoint **and** process) is realised as the
  spec-blessed *fixed script template* — `sh -c '<constant script>' sh <port>` — with
  the host + path baked into the source-controlled script body and the validated
  `port` passed as the sole positional `$1`. The script string never varies per item,
  so the fan-out is still discrete argv run once per item, never a looping/variable
  shell command (S4 preserved). The process probe additionally aggregates across a
  port's worker PIDs (gunicorn/uvicorn) inside that fixed script.
- **Pre-fill lives on the recipe (`V11__recipe_app_port_list.sql`).** The
  `(app-name, port)` list is one runtime value shared by all a recipe's probe
  actions, stored as `recipe.app_port_list` — the same `[{"appName","port","runtime"}]`
  JSON `RunService` binds per item, `@NotAudited` (not part of any content hash). The
  spec named no version; `V11` is the next free after `V10` (spec-022). Discovery
  refreshes it in place through `RecipeService.refreshDiscoveredAppPortList` every
  run, so a new/removed app reconciles onto the one recipe with no re-approval and no
  duplicate card (spec-021/022). `runtime` (`docker`/`systemd`/`process`) rides on
  each item; `RunService` ignores the extra field.
- **`fastapi` health-path configurability = editable fixed template, not a runtime
  param.** The default `/openapi.json` (and the actuator/`/metrics` paths) are fixed
  literals in the probe script; "configurable" means the operator edits the proposed
  action before approving (edit → re-approve), never a caller-supplied path param —
  adding a free-form path param would widen S4. The `/metrics` action is proposed only
  when the discovery `curl -sf .../metrics` GET responds (Prometheus present).
- **Container-name recovery from cgroup.** A cgroup segment that is a bare hex id
  can't be reconciled with a `DockerDiscoverer` name, so it is skipped and `appName`
  falls back to the cmdline-derived label; a human-readable docker segment (e.g.
  `/docker/orders-api`) becomes the `appName` + `runtime = docker` link.
- **Dashboard read surface.** The pre-filled apps surface on `GET /api/monitor` via
  `MonitorDtos.MonitorActionView.appPortList` (each fan-out probe action carries its
  recipe's apps) so spec-024's cards can group per app and know which ports to poll.
- **Tests.** `AppMonitorDiscovererTest` (classifier routing java→springboot,
  uvicorn/gunicorn→fastapi, else→generic; pre-filled items; docker double-detection;
  read-only-probes-only) and `AppMonitorReconcileTest` (re-discovery refreshes the
  list in place, no duplicate recipe). The fan-out execution itself stays covered by
  spec-022's `RunServiceTest`.

## Context

Beyond host vitals (023), a box typically runs several apps on different ports, and
they need per-app health/resource views. Concern 020 settled an **app-monitor
family**: sibling `MONITOR` recipes that share the fan-out `(app-name, port)` model
and the monitor UI (024), differing only in **how they probe**. Two strategies
compose: an **endpoint probe** (HTTP GET a health/metrics path) and a **process
probe** (resolve the listening port → PID via `ss -ltnp` → read `/proc/<pid>/`).
Discovery **classifies** each listening app and routes it to the matching recipe,
pre-filling the `(app-name, port)`.

## Decision

Three discovery-routed `MONITOR` recipes, each an action set parameterised by the
022 `APP_PORT_LIST` param (so each action fans out over the box's apps, S4-safe):

- **`springboot monitor`** — **endpoint-probe first** (actuator), **process-probe as
  a supplement**. One action per actuator endpoint: `health` (`/actuator/health`),
  `metrics` (`/actuator/metrics`, e.g. `jvm.memory.used`, `http.server.requests`),
  `beans` (`/actuator/beans`), `info` (`/actuator/info`). Runtime facts from
  `/actuator/info` + `java -version`.
- **`fastapi monitor`** — **process-probe** for real resource usage (FastAPI has no
  Actuator), **plus optional configurable endpoint probes**: a health-path (default
  `/openapi.json`, a liveness/"is this FastAPI" signal) and `/metrics` **when
  Prometheus is present**. Python version via `python --version` / cmdline.
- **`generic app monitor`** — **process-probe only** fallback for any listening app
  discovery can't classify. RSS/CPU/threads/uptime from `/proc`, no endpoint
  assumptions — guarantees every detected app gets real resource metrics.

All are `RecipeType.MONITOR`, approved once (no carve-out), polled by 024.

## Implementation

### Probe strategies (fixed, S4-safe templates)

**Endpoint probe** — a fixed single-app template, run once per `(app-name, port)`
item by 022's fan-out. E.g. `springboot monitor`'s `health` action argv:
`[LIT "curl", LIT "-s", LIT "-m", LIT "2", LIT "http://127.0.0.1:", PARAM "port",
LIT "/actuator/health"]` where `port` comes from the current fan-out item. The host
segment is the fixed literal `http://127.0.0.1:` (probe **localhost only** — apps
bind loopback; no remote target param), so the only varying token is the validated
`port`. Each item goes through the unchanged `ParamBinder.bind(...)`
([`recipe/service/ParamBinder.java:41`](../src/main/java/com/iskeru/computeadmin/recipe/service/ParamBinder.java)) —
discrete argv, no shell loop (022 S4 rule).

**Process probe** — a fixed template resolving the app's listener → PID → `/proc`
stats, also fanned out per item. It reads only the **login user's own** processes
(see Known Gaps / S5). Shape: from the item's `port`, find the owning PID and read
`/proc/<pid>/status` (`VmRSS`), `/proc/<pid>/stat` (CPU jiffies), thread/fd counts,
and `/proc/<pid>/cmdline`. Because `/proc/<pid>` paths embed a *resolved* PID (not a
static literal) the probe is a **fixed script template** whose only bound param is
the validated `port` — the PID lookup happens inside the fixed script from that
port, never as a caller-supplied token (preserving S4). gunicorn/uvicorn run several
worker PIDs per app, so the fastapi/generic process probe **aggregates across the
port's worker PIDs** (concern 020 note).

### Discovery routing (`discovery/service`)

A new `AppMonitorDiscoverer` implementing `RecipeDiscoverer`
([`discovery/RecipeDiscoverer.java`](../src/main/java/com/iskeru/computeadmin/discovery/RecipeDiscoverer.java)),
mirroring `DockerDiscoverer`'s structure
([`discovery/service/DockerDiscoverer.java:34`](../src/main/java/com/iskeru/computeadmin/discovery/service/DockerDiscoverer.java))
but classifying apps and routing to the right recipe:

1. **Enumerate listening app ports** — probe `ss -ltnp` (fallback `netstat -ltnp`),
   a fixed read-only probe (via the `Probes` helper the discoverers already share,
   per spec-006 implementation notes). Yields `(port, pid, cmdline)` for the login
   user's own listeners.
2. **PID → cmdline → classify** — read `/proc/<pid>/cmdline` (or `ps`), then:
   - **Spring Boot** — `java` + `-jar`/main-class, **or** `/actuator/health`
     responds → route to **`springboot monitor`**.
   - **FastAPI** — `uvicorn`/`gunicorn` in cmdline, **or** `/openapi.json` responds
     → **`fastapi monitor`**.
   - **otherwise** → **`generic app monitor`** (process-probe only).
3. **Propose the matching recipe pre-filled** with the app in its `APP_PORT_LIST`:
   the discoverer proposes (or, per 021, reconciles) one recipe per framework
   family per machine, and pre-fills the `(app-name, port)` item list from the
   classified apps. `app-name` is derived from the cmdline (jar name /
   `-Dspring.application.name` / uvicorn app path); `port` from `ss`.
4. **Recover container name to link the docker lens (022 double-detection).** For
   each PID, read `/proc/<pid>/cgroup`; if it resolves to a container, stamp
   `runtime = docker` and set `appName` to reconcile with the container name that
   `DockerDiscoverer`'s actions ([`DockerDiscoverer.java:52-69`](../src/main/java/com/iskeru/computeadmin/discovery/service/DockerDiscoverer.java))
   already carry — so the dashboard (024) shows **one** app card aggregating both
   the lifecycle lens (docker restart/logs) and the health lens (actuator). The
   recipes stay separate; only the card unifies. `runtime = systemd` / `process`
   otherwise.

All proposals land `PENDING_APPROVAL` through the existing `DiscoveryService`
persist path ([`discovery/service/DiscoveryService.java:98`](../src/main/java/com/iskeru/computeadmin/discovery/service/DiscoveryService.java)),
now idempotent (021) — re-discovery **refreshes the `(app-name, port)` list in
place** on a not-yet-approved recipe (a new app appears in the list) and surfaces a
diff on an approved one, never duplicating cards. The operator reviews/edits the
proposed apps before approving (concern 020).

The `(app-name, port)` list is a **runtime value, not part of the content hash**
(022) — so once `springboot monitor`'s `health` action is approved, adding/removing
apps needs **no re-approval**; discovery pre-fills and the UI edits the list.

### fastapi endpoint configurability

`fastapi monitor`'s endpoint probes are **optional and configurable**: a
health-path param defaulting to `/openapi.json`, and a `/metrics` action proposed
**only when** the discovery probe found Prometheus (`/metrics` responds). The
process probe is always proposed (the guaranteed resource signal).

**Tests.**
- `AppMonitorDiscovererTest` (fake `SshExecutor` with canned `ss`/`cmdline`/probe
  output): a java+`-jar` listener → `springboot monitor` pre-filled with its
  `(app-name, port)`; a uvicorn listener → `fastapi monitor`; an unknown binary →
  `generic app monitor`; a container-hosted Spring Boot app gets `runtime = docker`
  and a container-matching `appName` (the double-detection link). Assert **no
  mutating command** is ever probed (only read-only `ss`/`/proc`/`curl -s` GETs).
- `RunServiceTest` / fan-out (from 022): an approved `springboot monitor` `health`
  action with 2 `(app-name, port)` items runs the **fixed** `curl` template **twice**
  (assert 2 discrete argv lists, never one looping shell line) and labels output per
  `appName`.
- A reconciliation test (021): re-discovery with a **new** app in the list refreshes
  a `PENDING_APPROVAL` recipe's `APP_PORT_LIST` in place (no duplicate recipe).

## Known Gaps

- **Login-user-only detection (S5).** `ss -ltnp` shows the socket owner's PID/cmdline
  and `/proc/<pid>/` is readable **only for the login user's own processes** without
  privilege. So both auto-detection and process-metric reads **miss apps run by
  other users**. Decision (concern 020 Q5): **probe as the login user only, no
  `sudo`** in the probe path — document the gap; an optional `sudo -n` read is
  forward work, weighed against S5.
- **Classification is heuristic.** cmdline/endpoint signals can misclassify (a java
  process that isn't Spring Boot, a non-uvicorn ASGI server) → it falls back to
  `generic`, which still yields resource metrics. The human reviews before
  approving.
- **Loopback-only endpoint probes.** The endpoint probe targets `127.0.0.1`; an app
  bound only to a non-loopback interface (or in a container with a private network)
  may not answer — the process probe still covers it, and the container link (022)
  handles the dockerised case.
- **Attacker-influenced proposals (S3).** Discovered app names/ports become
  pre-filled `APP_PORT_LIST` items; the 004 human approval is the mitigation (as in
  spec-006).

## Sequencing

Depends on **022** (param + fan-out + convention). Builds in parallel with **023**
once 022 lands; the dashboard (024) renders these cards. Ops chips on the cards come
from **026**.
