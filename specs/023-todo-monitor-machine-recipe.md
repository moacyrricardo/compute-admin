# 023 — `monitor machine` recipe (host vitals)

> **Status:** todo. Linear BLOCKED — commits use `spec-023`. Graduated from concern
> [020](./020-todo-machine-monitoring.md). **Depends on
> [022](./022-todo-monitoring-foundations.md)** (`RecipeType.MONITOR`,
> classification convention) and transitively **[021](./021-todo-discovery-idempotency.md)**
> (so re-discovery reconciles the universal host monitor instead of duplicating it).

## Context

Every reachable box needs a baseline **host-vitals** view: CPU, RAM+swap, disk.
This is the host-level counterpart to the app-monitor family (025): concern 020's
built-in **`monitor machine`** recipe. It is **read-only**, **universal**
(proposed on every reachable machine, not gated on any installed service), and has
**no app param** — so under the 022 classification convention it is the canonical
*host-level* monitor: a `MONITOR` action **without** an `APP_PORT_LIST` param, which
is exactly how the dashboard (024) routes it to the host panel rather than an app
card.

## Decision

A single **`MONITOR`-typed** recipe named **`monitor machine`**, discovery-proposed
on **every** reachable box (universal, unlike the service-gated discoverers), with
three read-only actions — **cpu**, **memory**, **disk** — each a fixed argv template
with no params. Approved once like any recipe (no carve-out); polled by the browser
dashboard.

## Implementation

**A `MonitorMachineDiscoverer` (`discovery/service`)** implementing the
`RecipeDiscoverer` port
([`discovery/RecipeDiscoverer.java`](../src/main/java/com/iskeru/computeadmin/discovery/RecipeDiscoverer.java)),
mirroring `DockerDiscoverer`
([`discovery/service/DockerDiscoverer.java:34`](../src/main/java/com/iskeru/computeadmin/discovery/service/DockerDiscoverer.java))
but **universal** — it does **not** gate on a `command -v` probe (every Linux box
has these tools or a documented fallback), so it always proposes the recipe. It
returns one `ProposedRecipe(RecipeType.MONITOR, "monitor machine", <desc>, actions)`
with three `ProposedAction`s, each built from the `Proposals` helpers (`literal(...)`,
as `DockerDiscoverer` uses at
[`DockerDiscoverer.java:48-69`](../src/main/java/com/iskeru/computeadmin/discovery/service/DockerDiscoverer.java)).
All land `PENDING_APPROVAL` via the existing `DiscoveryService` persist path
([`discovery/service/DiscoveryService.java:98`](../src/main/java/com/iskeru/computeadmin/discovery/service/DiscoveryService.java)),
now idempotent per 021 (re-running keeps one `(machine, MONITOR, "monitor machine")`
recipe).

**Actions (fixed, read-only, no params, `sudo = false`).** Each is a single
primary command with a documented fallback noted for the operator (the fallback is
a *separate authored template choice*, not a runtime branch — pick the primary; the
fallback is documented so a future refinement can add a per-OS variant):

- **cpu** — `top -bn1` (one-shot snapshot; the header carries load average and
  %Cpu). Fallbacks documented: `uptime` (load average only) and reading
  `/proc/stat` (compute utilisation from two samples). Primary template argv:
  `[LIT "top", LIT "-bn1"]`.
- **memory** — `free -m` (both physical mem **and** swap, in MiB). Fallback
  documented: parse `/proc/meminfo`. Argv: `[LIT "free", LIT "-m"]`.
- **disk** — `df -h` (human-readable filesystem usage). Argv: `[LIT "df", LIT "-h"]`.

These are exactly the commands concern 020 named ("CPU usage — `top -bn1` /
`/proc/stat` / `uptime`; RAM+swap — `free -m` / `/proc/meminfo`; disk — `df -h`").
No `APP_PORT_LIST` param → no fan-out → the run path is the plain scalar
`RunService.run(...)` ([`run/service/RunService.java:101`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java))
N=1 case, unchanged by 022.

**Output is raw text; parsing is the UI's job.** Per concern 020 and ARCH.md's
"no server-side templating; JSON-driven vanilla JS", these actions return the tool's
raw stdout; the dashboard (024) parses `top`/`free`/`df` output client-side into the
host-panel bars (with amber/red thresholds). Keeping the recipe a dumb command and
the parsing in the browser means no server-side sampler and no parsed-value storage
(v1 no time-series).

**Registration.** `MonitorMachineDiscoverer` is a `@Component`, so Spring injects it
into the `List<RecipeDiscoverer>` that `DiscoveryService` already fans over
([`DiscoveryService.java:52,91`](../src/main/java/com/iskeru/computeadmin/discovery/service/DiscoveryService.java)) —
no wiring change. Because it is universal, it proposes on the same
`POST /api/machines/{id}/discover` call as the service discoverers.

**Tests.**
- `MonitorMachineDiscovererTest` (fake `SshExecutor`): proposes exactly the
  `monitor machine` recipe with cpu/memory/disk actions, all `sudo = false`, no
  params, `RecipeType.MONITOR`; proposes **regardless** of probe output (universal).
- `DiscoveryServiceTest` (extend): after discovery, `monitor machine` actions are
  `PENDING_APPROVAL`, and a **second** discovery does not duplicate them (021
  reconciliation) — the direct "no duplicate host panel" guard.
- A run test: an approved `cpu`/`memory`/`disk` action runs through the normal gate
  and returns the fake executor's stdout (the dashboard parses it).

## Known Gaps

- **Raw-text parsing is brittle across distros.** `top`/`free`/`df` output formats
  vary (BusyBox, non-GNU coreutils, locale). v1 parses the common GNU layout
  client-side; a per-OS/`/proc`-based variant (more robust, locale-proof) is a
  documented refinement — the fallbacks above are pre-noted for that work.
- **No time-series.** Each poll is a fresh snapshot; trends/history are a later
  concern (020).
- **CPU snapshot accuracy.** `top -bn1`'s single sample reports instantaneous
  %Cpu; a two-sample `/proc/stat` delta is more accurate but needs two reads — out
  of scope for the one-shot template in v1.

## Sequencing

Depends on **022** (needs `RecipeType.MONITOR`) and **021** (reconciliation). Can
build in parallel with **025** (app-monitors) once 022 lands; the dashboard (024)
needs **≥1** monitor recipe, and this is the simplest, so build it alongside/just
before 024.
