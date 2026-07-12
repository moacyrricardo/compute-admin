# 022 — Monitoring foundations (classification, app-label convention, fan-out run mode)

> **Status:** todo. Linear BLOCKED — commits use `spec-022`. This is the
> **decisions spec** graduated from concern [020](./020-todo-machine-monitoring.md):
> it pins the shared model that every monitor recipe (023, 025) and the app-ops
> facade (026) and the dashboard (024) build on. No UI and no specific recipes here.
> **Depends on [021](./021-todo-discovery-idempotency.md)** (re-discovery must
> reconcile, or the dashboard shows duplicate app cards).

## Context

Concern 020 settled the *shape* of monitoring: a browser-polled UI over ordinary
monitor **recipe actions**, no server sampler, no time-series in v1; a
`monitor machine` host recipe plus an app-monitor family (`springboot` / `fastapi`
/ `generic`), all discovery-routed and approved once like any recipe. Two of its
Open Questions are **decided**: Q1 (accept per-poll `Run` rows in v1 + add run-row
pruning; a non-persisted "read-now" path is forward work) and Q2 (one action per
probe endpoint, each parameterised by a **repeatable `(app-name, port)` list**
executed by **fan-out**, the UI pivoting into per-app cards).

This spec pins the three shared primitives those decisions require, so 023–026 can
each assume them:

1. how a monitor action is **classified** so the UI can enumerate monitors without
   hard-coding a recipe list;
2. the **`appName` (+ optional `runtime`) label convention** that correlates an
   app's several actions (across the monitor lens *and* the app-ops lens) into one
   card;
3. the **repeatable-composite `(app-name, port)` `ParamKind` + fan-out run mode** —
   the delicate piece, because it must preserve the spec-004/S4 injection guarantee.

## Decision

### 1. Classification — `RecipeType.MONITOR` (display metadata, not a gate)

Add **`MONITOR`** to `RecipeType`
([`recipe/model/RecipeType.java:10`](../src/main/java/com/iskeru/computeadmin/recipe/model/RecipeType.java)).
A monitor recipe holds **only** monitor actions. Classification is **per-recipe**,
matching how `NGINX`/`DOCKER`/… already tag a recipe. Consistent with that enum's
Javadoc ("Display/grouping metadata … it does not itself gate execution — the
approval state does"), `MONITOR` is **display/grouping metadata only**: it changes
nothing about the gate. A `MONITOR` action runs iff it is `APPROVED`, exactly like
any other action; there is **no read-only carve-out** and no auto-approval (concern
020's "Approval model" note — one approval already unlocks unlimited polled runs).

The **host-vs-app** distinction the dashboard needs is **derived, not stored**: an
app-level monitor is any `MONITOR` action carrying the `(app-name, port)` param
(§3); a host-level monitor (`monitor machine`, spec 023) is a `MONITOR` action
*without* it. The UI keys off "is it `MONITOR`? does it have the app param?", never
off which recipe produced the action — that is what lets `springboot`, `fastapi`,
and `generic` recipes coexist under one dashboard.

### 2. The `appName` (+ optional `runtime`) label convention — NOT an `App` entity

Correlation across an app's several actions (a `health` action, a `beans` action,
a `restart` op) is done by a **lightweight label convention**, deliberately **not**
a first-class `App` JPA entity. The repeatable `(app-name, port)` fan-out (§3) means
one action probes **many** apps, so there is no clean 1:1 `App ↔ Action` to hang an
entity off; promoting to an entity is explicitly **later work** if loose
correlation proves insufficient.

Convention:
- **`appName`** — a string identifier for the app (`orders`, `billing`). For an
  app-monitor action it is simply the `app-name` component of each `(app-name,
  port)` item (per-poll, per-item); discovery pre-fills it from the process cmdline
  (025). For an app-ops action it is a fixed label on the action (026).
- **`runtime`** (optional) — an enum-ish string `docker | systemd | process`
  recording *how* the app is realised on the box. Discovery stamps it (a container
  → `docker`, a unit → `systemd`, a bare PID → `process`); it lets the UI show the
  right ops affordances and lets the double-detection link (below) reconcile.

The UI **correlates by string match on `appName`** (case-normalised). This is the
honest downside: correlation is loose string matching, so it needs **name
normalisation** and, later, a small **alias map** (e.g. container `orders-api` ↔
health-probe `orders`). v1 normalises (lower-case, trim, strip a known
`-api`/`-svc` suffix set) and documents the alias map as forward work.

**Double-detection → link, don't dedup.** A dockerised Spring Boot app is seen by
**both** the docker discoverer (lifecycle lens — `DockerDiscoverer`,
[`discovery/service/DockerDiscoverer.java:34`](../src/main/java/com/iskeru/computeadmin/discovery/service/DockerDiscoverer.java))
**and** the springboot monitor probe (health lens, 025). These are **not** merged
into one recipe. Instead the monitor probe, having found the app's PID via
`ss -ltnp`, recovers the **container name** from `/proc/<pid>/cgroup` and stamps the
**same `appName`** (+ `runtime = docker`) that the docker recipe's actions carry.
The dashboard then aggregates both lenses under **one app card** by `appName`
string match. Recipes stay separate; only the *card* is unified.

Where the label lives: `appName`/`runtime` are **not** new columns on `Action`
(that would not survive the fan-out, where one action serves many apps). They are
**per-item, derived** for monitor actions (from the `(app-name, port)` items) and a
**convention on the action name / a small structured suffix** for app-ops actions
(spec 026 pins that encoding). This spec fixes the *convention and its normalisation
rules*; 025/026 fix where each kind of action sources the label.

### 3. Repeatable-composite `(app-name, port)` param + fan-out run mode (S4-critical)

This is the load-bearing, injection-sensitive piece. Spec it fully.

**New `ParamKind.APP_PORT_LIST`** — add to
([`recipe/model/ParamKind.java:11`](../src/main/java/com/iskeru/computeadmin/recipe/model/ParamKind.java)).
It is a **repeatable composite**: a list of items, each `(appName, port)` where
`appName` is validated by an **anchored pattern** (a restrictive app-name charset,
e.g. `^[A-Za-z0-9._-]{1,64}$` — *not* a caller-supplied regex; the pattern is a
fixed constant for this kind, not the `REGEX` escape hatch) and `port` is an integer
in `1..65535` (reusing the `INT_RANGE` validation shape from
[`ParamBinder.java:114`](../src/main/java/com/iskeru/computeadmin/recipe/service/ParamBinder.java)).

**Fan-out is a property of the action, driven by the token that references an
`APP_PORT_LIST` param.** A monitor action's argv template
([spec-004 structured argv](./004-done-recipe-action-approval-gate.md)) is the
**fixed single-app template** — e.g. `curl -s http://127.0.0.1:{port}/actuator/health`
with tokens `[LIT "curl", LIT "-s", LIT "http://127.0.0.1:", PARAM "port",
LIT "/actuator/health"]` (023/025 pin exact templates). The `APP_PORT_LIST` param
supplies **N items**, and the engine runs that fixed template **once per item**,
binding `port` (and, where the template references it, `app-name`) as **discrete
argv tokens for that single item**, then **aggregates the labelled outputs**.

**The critical rule (preserves S4):** the engine **never builds a looping or
variable shell command**. Each fan-out invocation is the *same fixed template* bound
with *one* item's validated scalar values, going through the **existing
`ParamBinder.bind(...)`** ([`ParamBinder.java:41`](../src/main/java/com/iskeru/computeadmin/recipe/service/ParamBinder.java))
unchanged — discrete, validated argv, POSIX-quoted by the SSH adapter exactly as
today. The repeatable-ness lives **above** the binder (in the run orchestration),
never inside the command string. So the S4 guarantee (ARCH.md §gate, item 5) holds
per invocation, item by item, with zero change to the injection core.

**Where fan-out is implemented — `RunService` (`run` module).** Today
`RunService.run(machineId, actionId, params)`
([`run/service/RunService.java:101`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java))
binds once (`paramBinder.bind` at
[`RunService.java:120`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java))
and dispatches one async `execute(...)`. Fan-out generalises this:

- **Detection.** If the action declares an `APP_PORT_LIST` param, `run(...)` enters
  fan-out mode; otherwise it is the exact scalar path as today (one item, one bind,
  one execute — a fan-out of size 1, so the existing path is literally the N=1 case
  and stays untouched behaviourally).
- **Binding per item.** For each `(appName, port)` item, build a per-item scalar
  param map (the item's `port`/`app-name` plus any other scalar params the action
  declares) and call the **unchanged** `paramBinder.bind(action, itemParams)` →
  one validated argv. All items are bound **before** anything executes, so a single
  invalid item fails the whole run cleanly (nothing dispatched), preserving
  `ParamBinder`'s "invalid ⇒ nothing is bound" contract.
- **Gate is checked once, for the action** (approval + live-hash re-check at
  [`RunService.java:109-116`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java)) —
  the fixed template is what was approved; the item list is a **runtime value**, not
  part of the content hash (concern 020 Q2: "the app list is a runtime value, not
  part of the content-hash … changing which apps are probed needs no re-approval").
- **Persistence (Q1 decision — accept per-poll rows in v1).** v1 persists **one
  `Run` row per poll**. Two acceptable shapes; **pick the parent+children shape**:
  a **parent `Run`** for the fan-out invocation and **one child `Run` per item**,
  the parent aggregating child status (`DONE` iff all children `DONE`, else
  `FAILED`). This keeps each child a faithful spec-005 record ("recording exactly
  what will run" — one item's argv, one exit code) while giving the poll a single
  handle. Add a nullable `parent_run_id` self-reference on `Run`
  ([`run/model/Run.java:49`](../src/main/java/com/iskeru/computeadmin/run/model/Run.java))
  + migration `V9__run_fanout_parent.sql`. Each child streams under its own run id
  through the existing `RunOutputHub`; the dashboard subscribes per child (or reads
  the persisted per-item output) and labels it by its item's `appName`.
- **Aggregation & labelling.** Output is aggregated **by label**: each item's
  stdout/stderr is tagged with its `appName` so the UI can route `orders`'s health
  JSON to the `orders` card and `billing`'s to the `billing` card. The aggregation
  is presentation metadata (the `appName` label per child run) — the raw per-item
  output stays exactly what the fixed template produced.

**Run-row pruning (Q1 forward-work, built here).** Polling at 5s ≈ 12 parent runs +
12·N child runs per minute per action, so unbounded `Run` accumulation must be
bounded. **Extend spec-013's eviction** — which today only reaps in-memory output
channels via `RunOutputEvictionJob`
([`run/service/RunOutputEvictionJob.java:18`](../src/main/java/com/iskeru/computeadmin/run/service/RunOutputEvictionJob.java))
and `RunOutputHub.evict(...)` — with a sibling **`RunRowEvictionJob`** (`@Scheduled`,
same `ca.*`-namespaced cron/retention config style) that deletes **terminal `Run`
rows** (and their children) older than a retention window
(`ca.run.row-retention`, default e.g. `24h`) and/or beyond a per-action cap
(`ca.run.rows-per-action-max`), never deleting a non-terminal (`QUEUED`/`RUNNING`)
row. Deletion goes through a repository method on `RunRepository`
([`run/repository/RunRepository.java`](../src/main/java/com/iskeru/computeadmin/run/repository/RunRepository.java));
`Run` is an append-only, non-audited log per ARCH.md, so bulk-deleting old rows is
safe and needs no Envers handling. The **non-persisted "read-now" path** (skip the
`Run` row entirely for read-only polled reads) remains the better end-state and is
**forward work**, not built here.

## Implementation

- `recipe/model/ParamKind.java` — add `APP_PORT_LIST`.
- `recipe/model/RecipeType.java` — add `MONITOR`.
- `recipe/service` — represent an `APP_PORT_LIST` `ParamDef` (its item schema:
  the fixed app-name pattern constant + the `1..65535` port bound). Extend
  `ActionService`'s add/edit validation
  ([`recipe/service/ActionService.java`](../src/main/java/com/iskeru/computeadmin/recipe/service/ActionService.java))
  so an action may declare **at most one** `APP_PORT_LIST` param and the argv
  template referencing it is the fixed single-app template. Extend
  `ActionSnapshot.hash` coverage so the *kind* is part of the hash (the template +
  schema), while the **item values remain runtime-only** (never hashed).
- `recipe/service/ParamBinder.java` — **unchanged for scalar binding.** Fan-out
  does **not** live here; the binder keeps validating/binding one scalar map at a
  time. Add only the per-item validation of an `APP_PORT_LIST` item's `appName`
  (anchored constant pattern) and `port` (range) — reusing the existing
  ALLOWED_SET/INT_RANGE-style guards
  ([`ParamBinder.java:85-129`](../src/main/java/com/iskeru/computeadmin/recipe/service/ParamBinder.java)).
- `run/service/RunService.java` — fan-out orchestration around the **unchanged**
  bind + the existing async `execute(...)` per item; parent/child `Run` rows;
  per-item labelled output. The gate checks
  ([`RunService.java:109-116`](../src/main/java/com/iskeru/computeadmin/run/service/RunService.java))
  run once per action, before any item executes.
- `run/model/Run.java` + `V9__run_fanout_parent.sql` — nullable `parent_run_id`.
- `run/service/RunRowEvictionJob.java` + `RunRepository` deletion finder +
  `V*`-free config keys (`ca.run.row-retention`, `ca.run.rows-per-action-max`,
  `ca.run.row-eviction-cron`).

**Tests.**
- `ParamBinderTest` (extend): an `APP_PORT_LIST` item with a bad app-name (spaces,
  shell metacharacters) or an out-of-range port is rejected; a good item binds to
  the fixed template's discrete argv (the S4 per-item guarantee).
- `RunServiceTest` (extend): a fan-out action with 3 items dispatches **3** child
  executions of the **same** template (assert against a fake `SshExecutor`'s
  recorded argv — 3 fixed-template argv lists, **never** a single looping/`&&`/`;`
  shell line: the direct S4 regression); a parent `Run` aggregates 3 children;
  one invalid item fails the whole run with **nothing dispatched**. An action with
  **no** `APP_PORT_LIST` param behaves exactly as the pre-022 scalar path (N=1).
- `RunRowEvictionJobTest`: terminal rows past retention are deleted (children with
  them); a `RUNNING`/`QUEUED` row is never deleted; the per-action cap trims oldest
  terminal rows.
- A classification test: a `MONITOR` recipe/action is **not** runnable unless
  `APPROVED` (the gate is unchanged — `MONITOR` grants nothing).

## Known Gaps

- **Loose string-match correlation.** `appName` correlation is normalised string
  matching; a mismatch between a container name and a probe-derived name splits one
  real app across two cards until an alias is added. The alias map is forward work.
- **Per-poll `Run` rows are a lot of rows.** v1 accepts them + prunes; the
  non-persisted "read-now" path is the real fix and is deferred (concern 020 Q1).
- **`APP_PORT_LIST` is the only structured param and only for monitors.** It is
  deliberately narrow — a fixed item schema, not a general "list of records" kind —
  to keep the S4 surface small. Generalising it is explicitly out of scope.
- **`MONITOR` classification is advisory.** Nothing prevents a `MONITOR` recipe from
  being authored with a mutating template; the gate still protects execution, but
  the "monitors hold only monitor actions" rule is a convention, not enforced by
  the type. (App-ops mutating actions are deliberately **not** `MONITOR` — see 026.)

## Sequencing

Depends on **021**. Prerequisite for **023** (monitor-machine), **024** (dashboard),
**025** (app-monitors), **026** (app-ops). Touches `recipe` (model + validation),
`run` (fan-out + pruning). No UI, no discoverers here.
