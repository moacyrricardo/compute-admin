# 026 — App-ops (an `app-name`-param facade over existing runtime recipes)

> **Status:** done. Branch `moacyrricardo/spec-026-app-ops` (stacked on the
> monitoring branch `moacyrricardo/monitoring-021-025`, #35). Linear BLOCKED —
> commits use `spec-026`. Graduated from concern
> [020](./020-todo-machine-monitoring.md). **Depends on
> [022](./022-done-monitoring-foundations.md)** (the reserved `app-name` param and its
> `ParamBinder.APP_NAME_PATTERN`) and **[024](./024-done-monitor-ui-dashboard.md)** /
> **[025](./025-done-app-monitor-recipes.md)** (the app cards these ops attach to).
> Built **after** monitoring — these are **mutating** actions and ride the unchanged
> 004/005 gate.

## Context

Once the dashboard (024) shows per-app cards, operators want to act on an app from
its card: **restart**, **redeploy**, **tail logs**. The temptation is a new mutating
recipe class ("app-ops"). That would be **wrong** and dangerous: it would create a
**second way** to `docker restart <container>` the same container, bypassing the
single approved definition and doubling the S4 surface.

## Decision

**App-ops is a CONVENTION / FACADE, not a new recipe class and not a new
`RecipeType`.** "restart / redeploy / tail-logs for app X" are realised by ordinary
**mutating actions** that already flow through the 004 gate. What ties an action to an
app is **one correlation key**, chosen to unify monitors and ops:

> **The correlation key is a reserved param named `app-name`.**

This is the same `app-name` the monitoring foundations (022) already introduced: the
per-item component of an `APP_PORT_LIST` (`ParamBinder.APP_NAME_COMPONENT`) validated
against the fixed anchored charset `ParamBinder.APP_NAME_PATTERN`. App-ops reuses that
name and that validator as a **scalar** param on ops actions. Concretely:

- An **ops action** (restart / tail-logs / redeploy) declares a **scalar param named
  `app-name`**, kind `ALLOWED_SET` — the set of apps that action can target (a single
  value is just an `ALLOWED_SET` of size one). It **may declare other params too**
  (e.g. `tail-logs` = `app-name` + `lines`).
- A **monitor** action already carries its app(s) as the `app-name` **component** of
  its `APP_PORT_LIST` fan-out (022/025). So both surfaces are keyed by the *same*
  `app-name` — monitors via the list component, ops via the scalar param.
- Whatever the kind, a bound `app-name` value is validated against
  `ParamBinder.APP_NAME_PATTERN` on **every** action (monitor or ops), so the
  correlation key can never widen the S4 surface.

**This is emphatically NOT** machine-tag correlation (tags are machine-scoped, they
group hosts, not apps) and **NOT** a recipe-level label. It is a param, gated and
validated exactly like every other param.

The gate is **unchanged**: an app-ops mutating action uses the **same approval path**
as any docker restart (004 REST-only approval, 005 run path). App-ops actions are
**not** classified `MONITOR` — they are ordinary `DOCKER`/`CUSTOM`/(new) `SYSTEMD`
actions; the `app-name` param is what the dashboard keys on.

## Implementation

### (a) The `app-name` param convention

- **Reserve + validate the name.** `ParamBinder` recognises a scalar param named
  `app-name` on **any** action and validates its bound value against
  `APP_NAME_PATTERN` (in addition to its own `ALLOWED_SET` membership). `ActionService`
  reserves the name at authoring time: a scalar `app-name` param must be `ALLOWED_SET`,
  and every allowed value must satisfy `APP_NAME_PATTERN`. `ParamBinder` exposes
  `isReservedAppNameParam(ParamDef)` / `hasReservedAppNameParam(Action)` /
  `targetApps(Action)` so both the binder and the dashboard agree on what counts as the
  correlation key and which apps an action can target.
- **The dashboard resolves apps → actions.** `MonitorService` enumerates, per machine,
  the **APPROVED** actions (of **any** recipe type) that carry a reserved `app-name`
  param — the machine's *ops actions* — alongside its existing `MONITOR` recipes. Each
  ops action exposes its **target apps** (the `app-name` `ALLOWED_SET` values). The UI
  surfaces on an app card (keyed by `appName`) **every approved ops action whose
  `app-name` param can target that app** (`targetApps` contains it / equals it), via the
  `MonitorDtos.opsForApp(machineView, appName)` resolver.
- **Pre-filled run.** Running an ops action from the `orders` card **pre-fills
  `app-name=orders`** (locked, non-editable) and prompts for the remaining params
  (e.g. `lines`); then the **normal gate** runs (APPROVED + live-hash + param
  validation server-side). Only approved actions appear/run.

This adds **no new mutating capability**; it only *surfaces* existing approved mutating
actions under an app verb, correlated by one param.

### (b) `SystemdDiscoverer` (`discovery/service`)

Docker apps already get lifecycle ops (restart/logs) from `DockerDiscoverer` — **not
duplicated here**. Bare **systemd** apps get nothing comparable, so add a
`SystemdDiscoverer` implementing `RecipeDiscoverer`, **mirroring `DockerDiscoverer`**:

- **Probe (fixed, read-only):** `command -v systemctl`, then
  `systemctl list-units --type=service --state=running --no-legend` to discover an
  `ALLOWED_SET` of running unit names (attacker-influenced input, S3 — the 004 human
  approval is the mitigation, as in spec-006). Units are filtered to `APP_NAME_PATTERN`.
- **Proposed actions** (all `PENDING_APPROVAL`, gated exactly like docker's), keyed by
  the reserved `app-name` param (the discovered unit **is** the app):
  **status** (`systemctl status {app-name}`, read-only), **restart**
  (`systemctl restart {app-name}`, `sudo -n`, S5), **tail-logs**
  (`journalctl -u {app-name} -n {lines}`, bounded `lines` `INT_RANGE`). `RecipeType` is
  the new `SYSTEMD` value (a display family beside `DOCKER`; `@Enumerated(STRING)`, so
  **no migration**).

### (c) `tail-logs` — bounded fits today; **follow mode built via run cancellation**

- **Bounded tail (fits the engine as-is).** `journalctl -u {app-name} -n {lines}` is
  **one-shot**: it prints N lines and exits, an ordinary approved action run through
  `RunService.run(...)` and streamed to completion via `RunOutputHub`. **No engine
  change.**
- **Follow mode (`-f`) requires RUN CANCELLATION** — a stream that never exits needs a
  way to stop a `RUNNING` run. Implemented here (minimal but real):
  - **`RunStatus.STOPPED`** — a new terminal state beside `DONE`/`FAILED`/`INTERRUPTED`
    (`Run.status` is `@Enumerated(STRING)`, so **no migration** for a new value).
  - **`RunService.cancel(runId)`** — owner-scoped (`requireRun`, 404 for not-owned);
    a no-op on an already-terminal run; otherwise marks the run `STOPPED` and, **after
    commit**, signals the transport to close the channel and completes the run's hub
    channel so subscribers detach cleanly. `finish(...)`/`markRunning(...)` no longer
    override a run that is already terminal, so cancellation wins the race with the
    returning `execStreaming`.
  - **`SshExecutor` cancellation** — the port gains `execStreaming(..., cancelKey)`
    (registers the live exec under a key) and `cancel(cancelKey)` (closes that exec's
    `ChannelExec`, so `execStreaming` returns). `MinaSshExecutor` tracks the live
    channel per run id and closes it on cancel; the defaults are no-ops so adapters
    without cancellation (`LocalDevSshExecutor`) and test fakes need no change.
  - **`POST /api/runs/{id}/cancel`** (owner-scoped) + a **Stop** control on the
    streaming run view.

### (d) redeploy stays `CUSTOM`/blueprint

Redeploy is app-specific (build → migrate → restart), already expressible as a
`CUSTOM` action or a blueprint (spec 007/010). **No new recipe type.** The facade
surfaces an approved redeploy `CUSTOM` action under an app card by giving it a reserved
`app-name` param (via `ActionService.addCustomAction`'s `paramDefs`). It inherits
spec-007's path-not-contents gap, resolved separately by spec 015 — out of scope here.

**Tests.**
- `SystemdDiscovererTest` (fake `SshExecutor`): running units become an `app-name`
  `ALLOWED_SET`; proposes status/restart(`sudo`)/tail-logs(bounded `-n`); **no
  mutating command probed**; all `PENDING_APPROVAL`.
- App-name correlation (`MonitorWebTest`): an approved ops action with an `app-name`
  `ALLOWED_SET` surfaces in the machine's `appOps` with its `targetApps`; the
  `opsForApp` resolver returns exactly the ops that can target a given app; a
  non-approved ops action does not surface.
- Run cancellation (`RunServiceTest`): `cancel(runId)` on a `RUNNING` run closes the
  channel, marks it `STOPPED`, and completes the hub; a not-owned run id is 404;
  cancelling a terminal run is a no-op.

## Known Gaps

- **`LocalDevSshExecutor` cancellation** is a no-op (default) — follow-mode under the
  offline `localssh` profile would run to its own timeout; the real `MinaSshExecutor`
  path is the one that cancels.
- **Facade correlation is `ALLOWED_SET` membership** — an ops action correlates to an
  app only when the app is in its `app-name` allowed set; a regex `app-name` is not
  enumerable and does not surface on cards.
- **redeploy path-not-contents (S5/spec-007)** — content-pinning (spec 015) is the fix.
- **systemd `sudo` (S5)** — `systemctl restart` assumes passwordless `sudo -n`.
- **App-ops adds no new gate** — deliberately. Every op is an ordinary approved action;
  the facade only *surfaces* them, so it cannot become a bypass.

## Sequencing

Depends on **022** + **024/025**. The **run-cancellation** engine work is the pacing
item, delivered here with follow-mode; bounded tail + restart + redeploy + the
`SystemdDiscoverer` ride the unchanged run path.

## Implementation notes (as built)

- **Correlation is the reserved `app-name` param — as specified.** `ParamBinder` gained
  `isReservedAppNameParam`/`hasReservedAppNameParam`/`targetApps`, validates any bound
  `app-name` scalar against `APP_NAME_PATTERN`, and `ActionService` reserves the name at
  authoring time (scalar `app-name` must be `ALLOWED_SET` of valid app identifiers).
  `MonitorService` enumerates per machine the APPROVED actions carrying that param
  (`appOps`); `MonitorDtos.AppOpView` exposes `targetApps`, and `MonitorDtos.opsForApp`
  resolves an app → its ops. The UI renders an "App operations" card per app with
  pre-filled (`?app-name=…`) run chips; `screenRunForm` locks the `app-name` field.
- **`RecipeType.SYSTEMD`** was added (append-only `STRING` enum, no migration) for the
  `SystemdDiscoverer`, mirroring `DockerDiscoverer`; docker lifecycle ops were **not**
  duplicated. Its `status`/`restart`/`tail-logs` are keyed by the reserved `app-name`.
- **Run cancellation** landed as a new terminal `RunStatus.STOPPED` (no migration),
  `RunService.cancel(runId)` (owner-scoped, no-op on terminal, STOPPED-wins ordering via
  after-commit signalling and terminal guards in `finish`/`markRunning`), an
  `SshExecutor.execStreaming(…, cancelKey)`/`cancel(cancelKey)` seam implemented by
  `MinaSshExecutor` (per-run channel registry), `POST /api/runs/{id}/cancel`, and a Stop
  control on the run view.
- **No schema change** was needed — both new enum values are `@Enumerated(STRING)`; the
  next free Flyway version (V12) was left unused.
- **The gate is untouched.** App-ops actions are ordinary approved actions; approval
  stays UI-only and `GateArchTest` still passes. `mvn -q -B clean verify` is GREEN
  (211 tests, 0 failures, 0 errors).
</content>
