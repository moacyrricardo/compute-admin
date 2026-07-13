# 032 — Monitoring axes foundations (the consumer model)

_Status: done · branch `moacyrricardo/spec-032-monitoring-axes-foundations` · Linear
blocked (no ticket)._

## Divergence from the spec

Implemented faithfully, with these notes:

- **H8 resolved by dropping**, not wiring. The spec offered "single server-side source
  of truth *or* dropped". Since there is no server-side metric sampler (the `/api/monitor`
  read only enumerates approved actions; the raw `free`/`/proc` stdout the axis math needs
  is produced by client-driven runs, spec-029), the `memPctOfHost`/`parseHostMemTotalMb`
  helpers could not become a *used* production source of truth without a redesign the spec
  defers. They were dead (test-only), so they were dropped and their tests removed; the
  client `clientMemPct` in `app.js` remains the single source of truth for the mem axis.
  No cpu/disk server twins were added (the spec's explicit anti-pattern). The other H8
  member the catalog names — `MonitorDtos.opsForApp` — is **out of scope** for 032 (this
  spec scopes H8 to the mem-axis helpers) and was left untouched.
- **The metric-kind classifier is client-side (`checkKind`), plus the existing
  MONITOR+APP_PORT_LIST server classification.** The spec said "extend `metricKind(action)`
  (client) and the server-side classification". `metricKind()` already classifies *host*
  CPU; the app-level CPU probe is an *app check*, so the client extension landed in
  `checkKind()` (the app-check classifier). No new server-side `MetricKind` enum was
  added — it would have been dead (the server assembles identity only; per-axis values
  are client-computed), the same anti-pattern H8 warns against. The server already
  "recognises" the cpu probe as a MONITOR action carrying an `APP_PORT_LIST` param.
- **Consumer axes and datastore/bucket labels are null at assembly.** Native discovery
  attaches only the `runtime` label today, so `MonitorConsumerView` maps native apps to
  `role = APP`, `source` from `runtime` (docker ⇒ DOCKER, else NATIVE), and leaves
  ram/cpu/disk/dedication/owner/usedBy/bucket/services null/empty — exactly the contract
  spec-033/034 fill in. `consumers` ships alongside the 029 `MonitorAppView`; the UI still
  renders `apps`, so there is no visible change.

## Context

Spec-029 gave the fleet Monitor a single resource axis — **mem-% of host**, per app
(`clientMemPct` = RSS ÷ host total). The docker-monitoring work
([concern 030](./030-todo-docker-container-monitoring.md)) and the Monitor UI/UX
redesign ([034](./034-todo-fleet-monitor-ui-redesign.md)) both need a richer, shared
shape that neither can own alone:

- **three axes** — RAM, CPU, disk — for every monitored thing, each expressed as a
  **% of its host**, so a machine reads as 100 % and every consumer is a share of it;
- a single **consumer** abstraction that a native process (spec-025) and a docker
  compose project ([033](./033-todo-docker-container-discovery.md)) both map onto;
- vocabulary the UI slices by: **role** (app / database / other), **source**
  (native / docker), **dedication** (dedicated / shared, for datastores), and the
  synthetic **bucket** consumers (`docker` unclassified, `system` remainder).

This spec is the **foundations** — the metric-kinds and DTO contract — mirroring how
spec-022 pinned the monitoring model before 023–026 built on it. It deliberately does
**not** add docker or the redesigned UI; it makes both buildable in parallel against a
fixed contract. Disk is defined here but only *native/generic* CPU is produced here —
docker fills in the docker-side numbers in 033.

## Decision

1. **The three axes are host-relative percentages.** RAM/CPU/disk are each reported as
   `0..100` = share of that machine's total (RAM: RSS ÷ host RAM; CPU: consumer CPU ÷
   host cores; disk: bytes ÷ the docker/data filesystem total). `null` = "no honest
   number for this axis" (rendered `—`), which is the normal state for **disk on a
   native process** and for any axis whose monitor isn't approved. Percentages never
   silently become 0 — absent is `null`, present is a number.

2. **CPU becomes a first-class monitor metric-kind.** Add `cpu` alongside the existing
   `memory`/`disk`/`cpu`(host) kinds so an *app-level* CPU probe is classifiable. Native
   app CPU is the **process-tree** sum (the app's PID plus children), sampled from
   `/proc/<pid>/stat` deltas or `ps -o %cpu`; the one-shot-sample caveat (spec-023) and
   the shared-memory/backend double-count caveat for multi-process apps (postgres) are
   documented, not solved, here.

3. **A consumer is the unit the Monitor aggregates and the UI renders.** The
   `/api/monitor` read assembles, per machine, a list of **consumers**, each carrying:
   `id, name, role (APP|DATABASE|OTHER), source (NATIVE|DOCKER), ram, cpu, disk (nullable),
   dedication (DEDICATED|SHARED|null), owner (appName|null), usedBy (appName[]|null),
   bucket (null|DOCKER|SYSTEM), services[]`. Native apps and docker compose projects both
   become consumers; the two synthetic buckets are consumers with `bucket` set and
   `probes` empty.

4. **Dedication is the honest datastore axis, decided here so 033/034 agree.** A
   datastore is **DEDICATED** when it belongs to exactly one app (a compose project's own
   db) — its resource is attributable, so an owner split is legitimate. It is **SHARED**
   when used by many apps with no single owner (a native postgres, a standalone redis) —
   a real footprint but **no per-app split**; `usedBy` lists the consumers, `owner` is
   null. This is the distinction the databases lens (034) draws.

5. **Buckets keep the bars honest.** `DOCKER` (containers not classified as app or
   datastore) and `SYSTEM` (OS + other processes + free — the remainder to 100 %) are
   consumers hidden by default and shown on demand, so the default view emphasises named
   consumers and the bars only fill to 100 % when buckets are revealed.

## Implementation

All new code cites `spec-032`.

- **`monitor/model` / metric-kinds.** Extend `metricKind(action)` (client) and the
  server-side classification so an app-level `cpu` probe is a recognised kind. The
  `appName`/`APP_PORT_LIST` fan-out convention (spec-022) is unchanged; a CPU probe is
  just another per-app check keyed by the reserved `app-name` param.
- **`MonitorDtos` — the consumer contract.** Introduce `MonitorConsumerView`
  (`id, name, role, source, ram, cpu, disk, dedication, owner, usedBy, bucket, services`)
  and `ConsumerServiceView` (`name, image, role, source, ram, cpu, disk`). `MonitorAppView`
  (029) is superseded by / mapped into `MonitorConsumerView`; keep the old view until 034
  swaps the UI so this spec stays non-breaking. **Enums** `ConsumerRole`, `ConsumerSource`,
  `Dedication`, `Bucket` live in `monitor/model`.
  - **Resolve the H8 debt while here:** the dead `MonitorDtos.memPctOfHost` /
    `parseHostMemTotalMb` helpers (catalog **H8**, concern 031) either become the single
    server-side source of truth for the mem axis or are dropped — do not add cpu/disk as a
    *second* pair of unused helpers. One tested source of truth per axis.
- **Native CPU probe.** A `metric=cpu` MONITOR action template that reads the app's
  process-tree CPU (bounded, read-only, login-user). Discovery routing for it is a
  one-line extension of spec-025's classifier (the app-monitor recipe gains a cpu check);
  no new recipe type.
- **Disk shape only.** Define the `disk` axis on the consumer as nullable and default it
  to `null` for native/generic apps (no attributable disk). The *values* for docker
  consumers (writable layer + volumes) are produced by **033**; native datastores may
  populate disk best-effort where the data dir is readable, else `null`.
- **`MonitorService`** assembles `MonitorConsumerView`s from the machine's approved app
  checks; `dedication`/`owner`/`usedBy`/`bucket`/`role` are populated from labels the
  discoverers attach (native today; docker in 033). Owner-scoped and read-only as today;
  no gate change, `mcp/` untouched.

## Known Gaps

- **CPU sampling** stays one-shot (`top`/`ps`/single `/proc/stat` read); a two-sample
  delta is deferred (concern 031, spec-023 gap).
- **Multi-process app RAM/CPU** (postgres tree, shared_buffers) is summed naïvely; the
  shared-memory double-count is documented, not corrected, in v1.
- **Disk for native datastores** depends on data-dir readability (S5); `null` when the
  login user can't read it. Full per-engine data-dir discovery is 033/035 territory.
- No time-series/history — every poll is a snapshot (029/031 gap, unchanged).
- The contract is additive: `MonitorConsumerView` lands here; the UI keeps rendering the
  029 `MonitorAppView` until **034** switches over, so 032 ships without a visible change.
