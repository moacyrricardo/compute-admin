# 026 — App-ops (a label facade over existing runtime recipes)

> **Status:** todo. Linear BLOCKED — commits use `spec-026`. Graduated from concern
> [020](./020-todo-machine-monitoring.md). **Depends on
> [022](./022-todo-monitoring-foundations.md)** (the `appName`/`runtime` label
> convention the facade resolves against) and **[025](./025-todo-app-monitor-recipes.md)**
> (the app cards these ops attach to). Built **after** monitoring — these are
> **mutating** actions and ride the unchanged 004/005 gate.

## Context

Once the dashboard (024) shows per-app cards, operators want to act on an app from
its card: **restart**, **redeploy**, **tail logs**. The temptation is a new mutating
recipe class ("app-ops"). That would be **wrong** and dangerous: it would create a
**second way** to `docker restart <container>` the same container, bypassing the
single approved definition and doubling the S4 surface.

## Decision

**App-ops is a CONVENTION / FACADE, not a new recipe class and not a new
`RecipeType`.** "restart / redeploy / tail-logs for app X" are realised by the
**existing runtime recipes**:

- **restart** → `docker restart <container>` (already shipped by `DockerDiscoverer`,
  [`discovery/service/DockerDiscoverer.java:53`](../src/main/java/com/iskeru/computeadmin/discovery/service/DockerDiscoverer.java))
  **or** `systemctl restart <unit>` (systemd, added here) **or** a `CUSTOM` script;
- **redeploy** → a `CUSTOM`/blueprint script (spec 007/010) — **no new recipe type**;
- **tail-logs** → `docker logs --tail`/`journalctl -n` (bounded, one-shot) — with
  **follow mode** requiring an engine addition (see (c)).

App-ops is therefore an **`appName` + `opKind`** (`restart | logs | redeploy`)
**label over existing/new mutating actions**, surfaced under an app-centric verb on
the card. It must **never** be a second path to `docker restart` the same container —
it resolves to *whichever already-approved action is that op for that app*. The gate
is **unchanged**: an app-ops mutating action uses the **same approval path** as any
docker restart (004 REST-only approval, 005 run path). App-ops actions are **not**
classified `MONITOR` — they are ordinary `DOCKER`/`CUSTOM`/(new) systemd actions; the
label is what the UI keys on.

## Implementation

### (a) The label / op-kind convention

The dashboard resolves "restart `orders`" → the already-approved action that *is*
the restart for `orders`. This needs the app-ops label attached to the relevant
existing actions. Per 022, `appName`/`runtime` are **not** columns on `Action`; the
app-ops label is a **small structured convention** the UI resolves:

- An action is an **op for app X** when its `(appName, runtime, opKind)` label
  matches. For a **docker** action (restart/stop/start/logs, `DockerDiscoverer`), the
  `container` `ALLOWED_SET` value **is** the app's container name; the 022
  double-detection link (025) already reconciles that container name to the app's
  normalised `appName` — so the UI matches `container == appName` (via the same
  normalisation/alias rules) and reads `opKind` from the action's role
  (restart/logs). For a **systemd** action (below) the unit name maps to `appName`
  the same way. For a **`CUSTOM`** redeploy the operator tags it with the app label
  at authoring time (a naming convention, e.g. recipe/action name carrying the app).
- The convention is pinned as a tiny, documented **label-resolution rule** in the
  monitor UI grouping code (024): given an app card's `appName`+`runtime`, find
  approved actions on the same machine whose target resolves to that `appName`, and
  classify each by `opKind`. **No model change** — it reuses the existing
  `container`/unit `ALLOWED_SET` values and 025's `appName` reconciliation.

This is exactly why app-ops is a facade: it adds **no new mutating capability**; it
only *surfaces* existing approved mutating actions under an app verb.

### (b) Optional `SystemdDiscoverer` (`discovery/service`)

Docker apps already get lifecycle ops (restart/logs) from `DockerDiscoverer`. Bare
**systemd** apps get nothing comparable today. Add an optional `SystemdDiscoverer`
implementing `RecipeDiscoverer`
([`discovery/RecipeDiscoverer.java`](../src/main/java/com/iskeru/computeadmin/discovery/RecipeDiscoverer.java)),
**mirroring `DockerDiscoverer`** exactly ([`DockerDiscoverer.java:34`](../src/main/java/com/iskeru/computeadmin/discovery/service/DockerDiscoverer.java)) —
**no model change**:

- **Probe (fixed, read-only):** `command -v systemctl`, then
  `systemctl list-units --type=service --state=running --no-legend` to discover an
  **`ALLOWED_SET` of running unit names** (attacker-influenced input, S3 — the 004
  human approval is the mitigation, as in spec-006).
- **Proposed actions** (all `PENDING_APPROVAL`, gated exactly like docker's):
  **status** (`systemctl status <unit>`), **restart** (`systemctl restart <unit>`,
  `sudo`), **journal** (`journalctl -u <unit> -n <tail>`, bounded `--tail` via
  `INT_RANGE` like docker's `logs` at
  [`DockerDiscoverer.java:65-69`](../src/main/java/com/iskeru/computeadmin/discovery/service/DockerDiscoverer.java)).
  The `unit` param is a closed `ALLOWED_SET` of the discovered units; `RecipeType`
  is a normal service type (reuse `CRON`/introduce nothing new — or a `SYSTEMD` enum
  value **only if** a display distinction is wanted; the facade does not require
  it). `sudo -n` per S5.

This gives bare-systemd apps the same lifecycle ops docker apps already have, so the
024 card's op chips work regardless of runtime.

### (c) `tail-logs` — bounded fits today; **follow mode needs run cancellation**

- **Bounded tail (fits the engine as-is).** `docker logs --tail N` and
  `journalctl -u <unit> -n N` are **one-shot**: they print N lines and exit, so they
  are ordinary approved actions run through `RunService.run(...)`
  ([`run/service/RunService.java:101`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java))
  and stream to completion via `RunOutputHub`. Docker's `logs` already exists
  ([`DockerDiscoverer.java:65`](../src/main/java/com/iskeru/computeadmin/discovery/service/DockerDiscoverer.java));
  systemd's `journal` is added in (b). **No engine change needed.**
- **Follow mode (`-f`) requires RUN CANCELLATION — a hard engine dependency.**
  `docker logs -f` / `journalctl -f` **never exit**; they stream until the client
  stops them. Today the run engine has **no way to stop a `RUNNING` run**:
  `RunService.execute(...)` calls `ssh.execStreaming(...)` and only returns when the
  channel closes ([`RunService.java:207-238`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java));
  there is no cancel path. So follow-mode logs **cannot** be built without adding
  run cancellation. **Spec that engine addition here as a prerequisite for
  follow-mode** (or clearly gate follow-mode behind it):

  **Run cancellation (new).**
  - A `RunService.cancel(runId)` entry point (owner-scoped like `requireRun`,
    [`RunService.java:145`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java))
    that signals the in-flight run to stop.
  - The `ssh` adapter (`SshExecutor` port) gains a way to **cancel the SSH channel**
    of a running exec — close the `ChannelExec`/session for that run so
    `execStreaming` returns. This means tracking the live channel per run id in the
    `run`/`ssh` layer (a cancellable handle), which the current
    fire-and-forget `execStreaming` does not expose.
  - A new terminal status **`RunStatus.STOPPED`** (beside `DONE`/`FAILED`/
    `INTERRUPTED`): a cancelled run is marked `STOPPED`, its `RunOutputHub` channel
    completed, so subscribers detach cleanly (reusing the spec-013 terminal-run /
    eviction handling, [`RunService.java:178-203`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java)).
  - The dashboard's follow-logs chip opens the stream and a **Stop** control calls
    `cancel(runId)`.

  Because cancellation is a **non-trivial engine addition** touching `run` + `ssh`,
  v1 of app-ops **ships bounded tail-logs only**; follow mode is delivered **with**
  the cancellation work — either in this spec's scope (if built) or explicitly
  flagged as its **hard dependency** so it is not silently assumed available.

### (d) redeploy stays `CUSTOM`/blueprint

Redeploy is app-specific (build → migrate → restart) and already expressible as a
**`CUSTOM` action or a blueprint** (spec 007 `ActionService.addCustomAction`; spec
010 blueprints for the shared `deploy.sh` case). **No new recipe type.** The app-ops
facade simply surfaces an approved redeploy `CUSTOM` action under the app card's
redeploy verb via the (a) label convention. It inherits spec-007's path-not-contents
gap (approval binds to the script path, not its bytes) — resolved separately by spec
015 (content-pinning), out of scope here.

**Tests.**
- `SystemdDiscovererTest` (fake `SshExecutor`): running units become a `unit`
  `ALLOWED_SET`; proposes status/restart(`sudo`)/journal(bounded `--tail`); **no
  mutating command probed**; all `PENDING_APPROVAL`.
- Label-resolution unit test (in the 024 grouping code): given an app card
  (`appName`, `runtime = docker`), the approved `docker restart` action whose
  `container` == the app resolves as its `restart` op; a `journalctl` action for the
  matching unit resolves as `logs`.
- If cancellation is built: `RunServiceTest` — `cancel(runId)` on a `RUNNING` run
  closes the SSH channel, marks it `STOPPED`, completes the hub channel; a
  not-owned run id is 404; cancelling a terminal run is a no-op.

## Known Gaps

- **Follow-mode logs blocked on run cancellation** — the load-bearing engine
  dependency above. Until it lands, only bounded tail is available.
- **Facade correlation is string-match** (022): resolving "restart X" relies on the
  container/unit name reconciling to `appName`; a mismatch means the card can't find
  the op until an alias is added.
- **redeploy path-not-contents (S5/spec-007)** — a `CUSTOM` redeploy trusts the
  script path, not its bytes; content-pinning (spec 015) is the fix, tracked
  separately.
- **systemd `sudo` (S5)** — `systemctl restart` assumes passwordless `sudo -n`; a
  per-command sudoers allowlist is the eventual hardening (ARCH.md S5).
- **App-ops adds no new gate** — deliberately. Every op is an ordinary approved
  action; the facade only *surfaces* them, so it cannot become a bypass.

## Sequencing

Depends on **022** + **025**; built **after** monitoring (024 renders the op chips).
The **run-cancellation** engine work is the pacing item — bounded tail + restart +
redeploy + the `SystemdDiscoverer` can land first; follow-mode logs land with
cancellation.
