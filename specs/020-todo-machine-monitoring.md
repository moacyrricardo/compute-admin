# 020 — Machine monitoring (concern)

> **Concern** (exploratory — options open, not a decided spec). Linear is BLOCKED;
> commits use `spec-020`. Graduates to a spec once the gate question (Q1 below) and a
> couple of details are settled. The overall shape is now fairly concrete (see Design);
> what stays open is mostly the read-only-approval decision.

## Problem

We want to **monitor** machine health — memory, disk, CPU — and also **per-app**
health (a single machine often runs several Spring Boot apps on different ports). It
must fit compute-admin's model: an SSH-recipe engine with a **UI-only approval gate**
on every action, no time-series storage, and no general scheduler. The tension is
between "run a read once and show it" and "keep it fresh" — resolved below by putting
the refresh loop in the **browser**, not the server.

## Design (the intended shape)

- **The monitor is a UI driven by a monitor recipe.** The dashboard renders the output
  of monitor **recipe actions** (the existing run/stream machinery), per machine — the
  monitoring "logic" is just recipes; the UI presents them.
- **Client-side polling setting.** The dashboard defaults to a **single request** (run
  once, show the result). The user can switch the refresh cadence to **every 5s / 30s /
  1min / 5min**, and the *browser* re-runs the actions on that interval. No server-side
  sampler, no stored time-series in v1: the dashboard shows the *current* reading,
  refreshed at the chosen cadence. (Historical charts / retention would be a separate,
  later concern.)
- **Built-in recipe: `monitor machine`.** Read-only actions for the core vitals:
  - **CPU usage** — `top -bn1` / `/proc/stat` / `uptime` load,
  - **RAM + swap usage** — `free -m` (or parse `/proc/meminfo`; both mem and swap),
  - **disk usage** — `df -h`.
- **App-monitoring recipes (a family; first one: `springboot monitor`).** For apps
  running on the box. `springboot monitor` is parameterised by **(app-name, port) pairs**
  — a machine can run **several** Spring Boot apps, so the recipe takes a repeatable
  `(app-name, port)` so each app is labelled and probed. Each app's read uses Spring
  Boot's **actuator endpoints** — `/actuator/health` plus others (`/info`, `/metrics`,
  e.g. `jvm.memory.used`, `http.server.requests`) — to build the per-app monitor view.
  Other app types (nginx status, a database ping, …) become sibling app-monitor recipes
  later.

## Open Questions

1. **The approval gate for read-only actions (the crux — must be decided before build).**
   Every action today needs UI approval before it runs. A dashboard that polls `free -m`
   every 30s cannot ask for approval each tick — so **read-only monitor actions must be
   runnable without per-run approval**. How do we grant that *without* denting the core
   invariant? Likely a structural **read-only action class** that may auto-run, guarded
   the same way the gate is today (a `GateArchTest`-style invariant: read-only actions
   carry no mutation, mutating actions stay strictly UI-approved). This is a real security
   decision, not an implementation detail.
2. **Repeatable composite `(app-name, port)` parameter (the biggest modelling question).**
   The current `ParamDef` binds a single scalar per param; `springboot monitor` wants a
   *list of `(app-name, port)` pairs* in one action (label + probe each app). Options:
   extend the param model to a repeatable composite; model each app as its own
   action/instance; or run once per pair and aggregate in the UI. Touches
   `ParamDef`/`ParamBinder` and possibly the action shape.
3. **Spring Boot health source:** actuator `/actuator/health` (assumes actuator exposed,
   maybe secured) vs a plain port-open/process check. Configurable per action?
4. **Dashboard polling mechanics:** re-invoke `run` per tick (a new `Run` row each poll —
   noisy, interacts with run retention/eviction) vs a lightweight "read now" path that
   doesn't persist a full Run for every poll. The read-only class (Q1) may want its own
   non-persisted execution path.
5. **View:** per-machine monitor panel vs a fleet overview; how the `monitor machine` and
   `springboot monitor` outputs are laid out (gauges? raw text? parsed values?).
6. **Discovery:** is `monitor machine` **auto-proposed** by discovery on every box (it's
   universal) or a fixed built-in; is `springboot monitor` proposed when a listening JVM
   is detected?

## Leaning (to confirm)

Build it as: a read-only **`monitor machine`** recipe (CPU, RAM+swap, disk) plus an
**app-monitor family** starting with **`springboot monitor`** (per `(app-name, port)`,
via actuator endpoints), surfaced on a **recipe-driven UI with a client-side poll setting
(default single; 5s/30s/1min/5min)**, **no server-side time-series in v1**. Resolve **Q1** first — a structural read-only action
class that runs without per-run approval while mutating actions stay gated — because
polling makes per-run approval impossible and this is the one genuine security decision.
Everything else (multi-port binding, actuator-vs-port-check, poll persistence) is
implementation detail to pin when this graduates to a spec.

## Sequencing

Independent of 018/019 but benefits from them (tag/filter which machines to monitor).
**Do not build until Q1 (the read-only-gate carve-out) is decided** — that must not be
left to the implementer.
