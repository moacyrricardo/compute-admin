# 029 — Fleet monitoring dashboard

_Status: done · branch `moacyrricardo/spec-029-fleet-monitoring` (stacked on #38 → #35
→ #37 → main) · Linear blocked (no ticket)._

## Context

The monitoring stack (specs 021–026) landed with a **single-machine** dashboard: the
Monitor screen scopes to one machine at a time, and its cards are enumerated **per
monitor action** (`health` / `metrics` / `process`) rather than per app — the card
title comes from `action.name`, and the `appName` pre-fill that discovery captures
(persisted as `Recipe.app_port_list` JSON, surfaced on `MonitorActionView.appPortList`)
reaches the browser but is **unused** by the monitor card renderer (`app.js` — see the
`appCard(action)` loop and the standing "per-app instances arrive once … 025 land"
comment). Meanwhile app-ops (spec 026) already renders **per app**, keyed off the
reserved `app-name` param's ALLOWED_SET (`targetApps`). So there are two parallel
structures, and no way to reason about the fleet as a whole.

Operators think in two directions at once: **broad** (which machines, filtered by
role/tag) and **deep** (a given app, compared across the machines that run it). A
single-machine picker serves neither. This spec makes the Monitor a **fleet view**
driven by filters, unifies monitoring onto a **per-app** card (checks + ops together),
and adds the one cross-machine metric that only makes sense in aggregate: an app's
**memory as a percentage of its host**.

UX reference: the interactive mock `monitor-mock-v3.html` (host-grouped sections, tag +
app-name filter chips, `no-apps` filter, per-app mem% bar, merged per-app cards, poll
control). Builds on 024 (UI), 025 (app-monitors), 026 (app-ops), 023 (host vitals),
018 (tags), 022 (`appName`/`APP_PORT_LIST` convention).

## Decision

1. **Fleet, not single machine.** The Monitor screen drops the machine dropdown and
   renders **one section per machine** (host vitals header + that machine's app cards).
   Machines in scope are chosen by filters, not a picker.

2. **Filter = scope = poll set.** Two filter dimensions, both client-side:
   - **Tags** (reuse spec-018 machine tags) select machines. Match semantics: a machine
     is in scope if it carries **any** selected tag (OR). No tag selected ⇒ all machines.
   - **App-names** select apps. Clicking a card's app-name pins it (same filter, second
     entry point). No app-name selected ⇒ all apps.
   - A synthetic **`no-apps`** app-filter token selects **host-only** machines (zero
     discovered apps), OR-combined with real app-names.
   - **Whatever is filtered out is not polled.** The client only issues probe runs for
     the visible (filtered-in) machines/apps; hidden ones cost nothing. A visible
     `polling N machines · M apps` counter makes the scope/cost explicit.

3. **Broad ↔ deep via the app-name filter.** With an app-name pinned, machines that do
   **not** run it **disappear entirely** (host-only boxes included, unless `no-apps` is
   also pinned) — leaving that app side-by-side across the machines that do, for
   comparison. Default on first open: **all machines**, poll = **Single** (one cheap
   one-shot; no standing load until the operator opts into an interval).

4. **Per-app card (unified).** Monitoring renders **per app**, not per action. Each card
   is keyed by `(machine, app-name)` and aggregates: the framework badge + UP/DOWN
   rollup, its **checks** (the fan-out probe results for that app), its **mem % of
   host**, and its **matched ops actions** (spec 026) — checks and ops on one card.

5. **Mem % of host.** Each app card's headline metric = app **RSS ÷ host total memory**,
   with a bar (warn ≥75%, bad ≥90%). RSS from the process probe (`VmRSS`), host total
   from `monitor machine` (`free -m`), correlated per machine. Same app on a smaller
   host reads hotter — the point of the cross-machine view.

6. **Prerequisite — 025 actuator-liveness correction (already landed on `#35`).**
   `AppMonitorDiscoverer.classify()` previously routed any `java` listener to
   `springboot` and blindly attached four `/actuator/*` probes **without checking any
   respond** — an actuator-less Spring Boot (e.g. one started by `nohup java -jar
   app.jar`) got a recipe of four dead probes and no useful card. This was fixed on the
   monitoring branch (commit `spec-025 Verify actuator liveness; fall back to http
   monitor`): at discovery the springboot branch **probes `/actuator/health`**
   (`curl -sf`, the same guard the fastapi branch uses for `/metrics`); if it responds →
   `springboot` family as-is; if **nothing** on `/actuator/*` responds → a **new `http
   app monitor` family** (HTTP liveness `GET /` + process probe). A distinct family keeps
   the process-only `generic` recipe free of bogus HTTP probes. The fleet UI renders an
   `http`-family app with an "actuator-less" note. **Not re-implemented here** — 029
   consumes it as a base-branch prerequisite.

## Implementation

### Backend

- **Fleet read endpoint.** Extend `MonitorService` / the monitor RS resource to return
  monitor state for a **set** of machines (filtered by tag on the server, or the client
  passes the in-scope machine ids). Keep the existing per-machine probe run; the fleet
  endpoint fans out over the in-scope machines only. Response groups by machine and, per
  machine, by app (`appName` from `parseAppPortList`), attaching each app's checks
  (fan-out probe results) and its `AppOpView`s (matched by `app-name`, spec 026).
- **Per-app rollup DTO.** Add a `MonitorAppView { machineId, appName, framework, runtime,
  port, up, rssMb, hostMemTotalMb, memPctOfHost, checks[], ops[] }` assembled from the
  existing `MonitorActionView.appPortList` items + the host-vitals view + the app-ops
  views. `memPctOfHost = round(rssMb / hostMemTotalMb * 100)`. This is the missing wiring
  the current renderer skips — identity comes from the `appName` pre-fill, not
  `action.name`.
- **Host total memory** is already collected by `monitor machine` (023); expose the
  parsed total (MB) on the host-vitals view so the app rollup can divide by it.
- **025 discoverer change** — done on `#35`, not part of the 029 build. The springboot
  branch probes `/actuator/health`; on no response it routes to the `HTTP` family
  (`http app monitor`: `liveness` `GET /` + `process`). `AppMonitorDiscovererTest`
  covers actuator-present (→ springboot unchanged) and actuator-less (→ http fallback).
  No gate/approval change (RecipeType is display-only; the fan-out template stays
  S4-safe). 029's frontend must treat the `http` family like `springboot`/`fastapi`
  (badge + checks), tagging it "actuator-less".

### Frontend (`app.js`, spec-024 view)

- Replace the machine dropdown with a **filter bar**: tag chips + app-name chips (+ the
  `no-apps` chip) + poll segment + the `polling N machines · M apps` counter.
- Replace the per-action `appCard(action)` loop with **per-machine sections** → **per-app
  cards** built from `MonitorAppView`. Reuse the app-ops card wiring (026) so a card shows
  both checks and ops.
- Client filter/poll logic: `visibleMachines()` = tag-match AND (no app filter ⇒ all;
  else runs a selected app, or is app-less when `no-apps` is pinned). Poll loop iterates
  **visible** machines/apps only.
- Rendering stays `textContent`/DOM-built and theme-aware (012 rules).

## Known Gaps

- **No server-side poll/scheduler.** Polling stays client-driven (024); closing the tab
  stops all probing. A background fleet poller is out of scope (would revisit 016/S7).
- **Tag OR only.** No AND/NOT tag expressions; a single OR-set is enough for v1.
- **mem% is RSS-only.** Swap-inclusive footprint is deferred; RSS ÷ host-total is the
  cheap, honest v1 metric. Container cgroup memory limits (vs host total) are not
  modeled — the denominator is always host RAM.
- **No fleet aggregate rollup** (e.g. "3/12 apps DOWN" banner) in v1 — the per-machine
  sections carry the state; a summary strip is a fast-follow.
- **Cross-machine app identity is by `app-name` string.** Two unrelated apps sharing a
  name on different machines will be compared as "the same app"; acceptable given
  operators choose the names.

## Delivered (how the implementation differed)

- **Backend.** `MonitorDtos.MonitorAppView { machineId, appName, framework, runtime,
  port, up, rssMb, hostMemTotalMb, memPctOfHost, checks[], ops[] }` is assembled per
  `(machine, app-name)` from each app-monitor recipe's `appPortList`, sharing the
  recipe's fan-out probes as `checks` and correlating app-ops by `app-name`. Consistent
  with the Known Gap "polling stays client-driven, no server sampler", the four live
  metrics are assembled `null`; the browser fills them from the poll. The server owns
  the pure, unit-tested helpers `memPctOfHost(rssMb, hostMemTotalMb)`,
  `parseHostMemTotalMb(freeOutput)` (the mem% denominator, spec §5) and
  `frameworkOf(recipeName)` (incl. the actuator-less `http` family). `GET /api/monitor`
  gained optional `?tag=`/`?machineId=` scoping (the client's visible set → never
  enumerated ⇒ never polled).
- **New read affordance.** To realise "attach each app's fan-out probe results" without
  a server sampler, added `GET /api/runs/{id}/children` (+ `RunDtos.ChildRunView` and
  `RunService.childRuns`, owner-scoped): after the browser POSTs a fan-out probe for the
  visible apps, it lists the parent's children (each carrying its `appLabel`) and streams
  each child's output, attributing `up`/`VmRSS`→`rssMb` back to the right app card. No
  gate/approval change (`GateArchTest` stays green).
- **Frontend.** The machine dropdown is replaced by a tag + app-name filter bar (with the
  synthetic `no-apps` chip, the poll cadence segment, and a `polling N machines · M apps`
  counter). Per-machine sections carry a compact host-vitals header and a per-app card
  grid built from `MonitorAppView`: framework badge (http tagged "actuator-less"),
  UP/DOWN rollup, a mem-%-of-host bar, per-check status chips, and pre-filled ops chips —
  checks and ops on one card. Clicking a card's app-name toggles the app-name filter; the
  poll loop touches only the visible sections. All DOM is `textContent`/`h()`-built
  (spec-012) and theme-aware.
- **Tests.** `MonitorRollupTest` (pure DTO assembly + `memPctOfHost`/`parseHostMemTotalMb`/
  `frameworkOf`), `MonitorWebTest` extended (fleet endpoint: `apps` present + `?machineId=`
  scoping), `RunServiceTest` extended (`childRuns` by app-label + 404 for another user's
  parent). Full `mvn clean verify` green (221 tests).
