# 024 — Monitor UI dashboard (host panel + per-app cards, client-side polling)

> **Status:** todo. Linear BLOCKED — commits use `spec-024`. Graduated from concern
> [020](./020-todo-machine-monitoring.md). **Depends on
> [022](./022-todo-monitoring-foundations.md)** (classification + fan-out output
> labelling) and **≥1 monitor recipe** — build after/with
> [023](./023-todo-monitor-machine-recipe.md); app cards come alive once
> [025](./025-todo-app-monitor-recipes.md) lands. A working interactive mock of
> exactly this dashboard exists; the layout below matches it.

## Context

Monitoring's "logic" is just recipes (022/023/025); this spec is the **UI that
presents them**. Per concern 020 it is a browser-polled dashboard with **no
server-side sampler and no stored time-series in v1** — the browser re-runs the
approved monitor actions on a chosen cadence and renders the *current* reading. It
must enumerate monitors **by classification**, not by a hard-coded recipe list, so
the `springboot`/`fastapi`/`generic` families (025) and the host monitor (023) all
appear without UI changes.

The existing UI is the framework-free, JSON-driven vanilla-JS shell from spec 012
(`src/main/resources/static/app.js`, ~1250 lines): an `h()` element helper that
**forbids `innerHTML`** ([`app.js:31`](../src/main/resources/static/app.js)) and
sets text via `textContent`/text nodes ([`app.js:30`](../src/main/resources/static/app.js)),
a hash router, an `api(...)` fetch client
([`app.js:114`](../src/main/resources/static/app.js)), and `streamRunOutput(...)`
which streams a run's SSE output via `fetch` with a Bearer header
([`app.js:145`](../src/main/resources/static/app.js)). The dashboard is a new view
in this shell, honouring the same token-based design system (`tokens.css`) and the
spec-012 XSS discipline.

## Decision

A **Monitor dashboard** view (a new hash route, e.g. `#/monitor` or
`#/machines/{id}/monitor`) that:

1. **Enumerates `MONITOR`-classified actions** for the current user's machine(s) via
   the existing list APIs, and **groups them by the 022 convention**: actions
   **without** the `APP_PORT_LIST` param → a **host panel**; actions **with** it →
   **per-app cards** keyed by normalised `appName`, each card also aggregating that
   app's linked **ops** actions (026) by `appName` match.
2. **Polls client-side.** Default **single** (run once, show result). A cadence
   selector offers **5s / 30s / 1m / 5m**; the browser re-runs the actions on that
   interval (`setInterval`, cleared on route-away). A **Run now** button and an
   **"updated Ns ago"** heartbeat. **No server sampler; no time-series.**
3. Provides a **per-app detail drawer** with KPIs, a **Runtime** block, the
   endpoints/actions probed, and a **Related actions** list runnable inline
   (gate-safe: only `APPROVED` actions run).

## Implementation

**Layout — matches the interactive mock.**
- **Host panel** (top). One block per machine, rendering the parsed `monitor
  machine` output (023): **horizontal bars** for CPU, RAM (+swap), disk, each with
  **amber/red thresholds** (e.g. amber ≥ 75%, red ≥ 90% — as `tokens.css` semantic
  colors, theme-aware). Parsing of `top`/`free`/`df` stdout is client-side (023
  Known Gaps: brittle across distros — parse the common GNU layout, degrade
  gracefully to raw text on parse failure).
- **Per-app cards** (grid below). One card per normalised `appName`, showing: a
  **framework badge** (`springboot` / `fastapi` / `generic`, from the app's
  recipe/runtime), an **UP/DOWN pill** (from the health/liveness probe result), key
  KPIs (RSS, CPU, health status), and a **"run" chip row** — a row of chips for the
  app's runnable **related actions** (health, beans, restart, tail-logs…), each chip
  a gate-safe inline run of an already-`APPROVED` action.
- **Per-app detail drawer** (opens from a card). KPIs; a **Runtime block** —
  springboot: Java version + JVM + base image + Spring Boot version (from
  `/actuator/info` + `java -version`, per 025); fastapi: Python version + server
  (uvicorn/gunicorn); generic: binary/base facts; the **endpoints/actions probed**;
  and a **Related actions** list (approved actions on this machine whose `appName`
  matches) **runnable inline from the drawer**.

**Enumeration & grouping (data flow).**
- Reuse the existing list endpoints to gather the user's recipes/actions and filter
  to `RecipeType.MONITOR` (022). The `ActionView` DTO
  ([spec-004 `RecipeDtos.ActionView`](./004-done-recipe-action-approval-gate.md))
  already exposes `approvalState`; extend the monitor-relevant views to expose the
  recipe **type**, whether the action declares an `APP_PORT_LIST` param, and (for
  app actions) the per-item `appName`/`runtime` labels (022) so the client can group
  without guessing. **No business logic in the client** beyond grouping + parsing;
  the gate stays server-side.
- Grouping key: `normalise(appName)` per 022 (lower-case, trim, strip known
  `-api`/`-svc` suffixes). The double-detection link (a dockerised Spring Boot app
  seen by both the docker lens and the springboot probe, 022/025) surfaces as **one
  card** because both carry the same normalised `appName`.

**Polling.**
- A cadence selector (`single | 5s | 30s | 1m | 5m`) stored in the view state;
  default `single`. On a non-single cadence, a `setInterval` re-invokes the same
  "run all visible monitor actions" routine; the interval is **cleared on
  route-away** and on cadence change (no leaked timers). A **Run now** button
  triggers one immediate cycle. An **"updated Ns ago"** label ticks from the last
  successful cycle's timestamp.
- Each cycle **runs the approved monitor actions** through the normal run path
  (`POST` the run, then `streamRunOutput` at
  [`app.js:145`](../src/main/resources/static/app.js) to collect stdout), i.e. the
  browser re-runs an already-approved action every tick — concern 020's "polling is
  not a gate problem." For a **fan-out** action (app monitors, 022), one run yields
  per-item labelled output (parent + child runs); the client routes each child's
  output to its `appName` card. An **unapproved** monitor action renders as a
  disabled card/chip with a link to the approval screen (spec 012) — never run.

**Gate-safety of inline runs.** Chips/related-action buttons call the same
`RunService` entry point (`POST /api/.../run`), which enforces approval + live-hash
+ param validation server-side ([`run/service/RunService.java:109-120`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java)).
The client **only offers** actions whose `approvalState === APPROVED`; a
not-approved action can't be run from the card. Mutating ops (restart via 026) run
through the **identical** path — no special client privilege.

**Rendering discipline (spec-012).** All dynamic strings — app names, framework
badges, parsed metric values, and especially **command stdout** (remote,
attacker-influenceable, spec-012 Known Gaps) — are rendered via `textContent` / the
`h()` helper's `text` prop; **never `innerHTML`** (the `h()` helper throws on an
`html` prop, [`app.js:31`](../src/main/resources/static/app.js)). Bars/pills/badges
are styled via `tokens.css` classes, **theme-aware** (light + dark via
`prefers-color-scheme`, honouring the viewer's theme), with WCAG-AA contrast and
visible keyboard focus (spec-012 accessibility rules).

**Files.** New render function(s) + a nav entry in
`src/main/resources/static/app.js`; dashboard-specific classes (bars, cards, pills,
chips, thresholds) in `src/main/resources/static/app.css` using `tokens.css`
variables; a nav link in `index.html`. No new server endpoint is strictly required
if the existing list + run + SSE APIs suffice; if enumeration needs a convenience
aggregate, add a read-only `*RS` returning a DTO (per ARCH.md — resources return DTO
records, no Spring MVC).

**Tests.** Per spec-012's testing posture the UI is vanilla JS (no JS unit harness
in-repo); verification is a `*WebTest` on any **new** REST aggregate (if added) plus
the standard live check (`/spec-test-live`): approve a `monitor machine` + a
`springboot monitor`, load the dashboard, confirm the host bars render, an app card
shows a framework badge + UP/DOWN pill, cadence polling refreshes the "updated Ns
ago" heartbeat, and an inline chip runs an approved action while an unapproved one
is disabled.

## Known Gaps

- **Client-side parsing is brittle** (023): `top`/`free`/`df` layouts vary; the
  dashboard degrades to raw text on parse failure rather than showing wrong bars.
- **String-match app correlation** (022): a name mismatch splits one app across two
  cards until an alias is added; the alias map is forward work.
- **Poll load** (022 Q1): a 5s cadence over many apps generates many runs; run-row
  pruning (022) bounds storage but the browser still issues the requests — a
  non-persisted read-now path is the eventual optimisation.
- **No fleet-wide overview / no history** in v1 (concern 020 Open Q4): the dashboard
  is per-machine current-state; a fleet roll-up and trend charts are later concerns.

## Sequencing

Depends on **022** and **≥1 monitor recipe (023)**; app cards are fully meaningful
once **025** lands and ops chips once **026** lands, but the dashboard can ship
against 023 first and light up app cards as those specs merge.
