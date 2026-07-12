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

- **A monitor dashboard driven by recipes.** The dashboard renders the output of
  monitor **recipe actions** (the existing run/stream machinery), per machine.
- **Client-side polling setting.** Each dashboard defaults to a **single request**
  (run once, show the result). The user can switch it to **poll** — every **30s / 1min /
  …** — and the *browser* re-runs the actions on that interval. No server-side sampler,
  no stored time-series in v1: the dashboard shows the *current* reading, refreshed at
  the chosen cadence. (Historical charts / retention would be a separate, later concern.)
- **Built-in recipe: `monitor machine`.** Read-only actions for the core vitals:
  - memory — `free -m` (or parse `/proc/meminfo`),
  - disk — `df -h`,
  - CPU / load — `uptime` / `top -bn1` / `/proc/loadavg`.
- **Built-in recipe: `springboot monitor`.** Parameterised by **one or more ports**
  (a machine runs many Spring Boot apps), producing a per-port health/metrics read —
  e.g. hit each app's actuator (`curl -s localhost:<port>/actuator/health`) or a
  port/process liveness check. The `port` parameter is validated (INT_RANGE) and
  **repeatable / multi-value** so one action covers several apps on the box.

## Open Questions

1. **The approval gate for read-only actions (the crux — must be decided before build).**
   Every action today needs UI approval before it runs. A dashboard that polls `free -m`
   every 30s cannot ask for approval each tick — so **read-only monitor actions must be
   runnable without per-run approval**. How do we grant that *without* denting the core
   invariant? Likely a structural **read-only action class** that may auto-run, guarded
   the same way the gate is today (a `GateArchTest`-style invariant: read-only actions
   carry no mutation, mutating actions stay strictly UI-approved). This is a real security
   decision, not an implementation detail.
2. **Multi-value `port` parameter.** The current `ParamDef` binds one value per param;
   `springboot monitor` wants several ports in one run. Extend `ParamKind` to a repeatable
   value, or run the action once per port and aggregate? Affects `ParamBinder`/argv binding.
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

Build it as: **read-only `monitor machine` + `springboot monitor` recipes**, surfaced on a
**recipe-driven dashboard with a client-side poll setting (default one-shot; 30s/1min/…)**,
**no server-side time-series in v1**. Resolve **Q1** first — a structural read-only action
class that runs without per-run approval while mutating actions stay gated — because
polling makes per-run approval impossible and this is the one genuine security decision.
Everything else (multi-port binding, actuator-vs-port-check, poll persistence) is
implementation detail to pin when this graduates to a spec.

## Sequencing

Independent of 018/019 but benefits from them (tag/filter which machines to monitor).
**Do not build until Q1 (the read-only-gate carve-out) is decided** — that must not be
left to the implementer.
