# Spec catalog

The architectural decision record for compute-admin. Each feature lands as a
numbered spec (`NNN-status-slug.md`, status `todo`/`doing`/`done` by file rename),
authored with `/new-spec`, grounded in [ARCH.md](../ARCH.md) for architecture and
[CONTRIBUTING.md](../CONTRIBUTING.md) for build/commit conventions and code style
(the build charter, mirroring `birthday-rsvp`). Linear is
**blocked** for this repo — specs carry no issue id and commits use `spec-NNN`.

Filename status is the source of truth once merged to `main`; the **Status** column
below also reflects in-flight PRs (a spec's file reads `todo` on `main` until its PR
merges and renames it).

## Specs

| # | Spec | Status | Notes |
|---|------|--------|-------|
| 001 | Project skeleton | ✅ done | on `main` |
| 002 | MCP transport seam | ✅ done | on `main` |
| 011 | User accounts, authentication & ownership | ✅ done | on `main` — auth **mechanism** superseded by 014 (email+password); JWT/tokens/pairing/ownership still authoritative |
| 003 | Machine registry, tagging, app keypair & SSH adapter | ✅ done | on `main` |
| 004 | Recipe & Action model, approval gate & audit | ✅ done | on `main` — the security core |
| 005 | Execution engine (async jobs + live streaming) | ✅ done | on `main` |
| 006 | Recipe auto-discovery | ✅ done | on `main` |
| 007 | Custom-command recipes | ✅ done | on `main` — groups multiple custom actions per recipe |
| 010 | Recipe blueprints (author once, instantiate per-machine) | ✅ done | on `main` |
| 008 | MCP write & run tools | ✅ done | on `main` — MCP actor-propagation resolved |
| 012 | Web UI shell, design system & the approval screen | ✅ done | on `main` — live-integrated |
| 013 | Runtime resource hygiene (H1/H3/H6) | ✅ done | on `main` — streaming eviction, tx scoping, one shared SSH client |
| 014 | Email + password authentication | ✅ done | replaces Google sign-in; supersedes 011's auth mechanism |
| 015 | Custom-script content-pinning | ⚪ todo | security: hash-at-approval + re-hash-at-run; resolves **H5**, hardens S5 |
| 016 | Graceful shutdown & run reconciliation | ✅ done | drain in-flight runs + boot reconciler for orphaned QUEUED/RUNNING rows; neighbor to S7 (out of scope) |
| 017 | Transaction-boundary strategy | ⚪ todo | **concern** (exploratory, options open) — `TransactionTemplate` (A, as-built) vs bean-refactor (B) vs `@Async`+future (C) for "I/O outside tx, persist in a short tx" |
| 018 | Machine tags: filtering & auto-tagging | ✅ done | filter machines by tag; auto-tag from login-user + OS/cloud probe |
| 019 | Event-driven connectivity status | ✅ done | `MachineReached` event + async listener updates status (fixes stale UNREACHABLE pill); manual test-connection |
| 020 | Machine monitoring | 🟢 graduated | **umbrella concern of record** — resolved into specs 021–026 + 029 (build order below); keeps the problem framing + the Q1/Q2 decisions |
| 021 | Discovery idempotency | ✅ done | resolves **H2** — re-discovery reconciles by `(machine, type, name)` instead of duplicating; refresh DRAFT/PENDING proposals in place, surface a diff on APPROVED-differs; uniqueness guard. **Monitoring prerequisite (build first)** |
| 022 | Monitoring foundations | ✅ done | the decisions spec — `RecipeType.MONITOR` (display-only, gate unchanged); `appName`(+`runtime`) label convention + double-detection link; `APP_PORT_LIST` param + **fan-out run mode** (S4-safe: fixed template per item, never a shell loop); run-row pruning (extends 013 eviction) |
| 023 | `monitor machine` recipe | ✅ done | universal read-only host vitals — cpu (`top -bn1`), ram+swap (`free -m`), disk (`df -h`); auto-proposed on every reachable box; no app param (→ host panel) |
| 024 | Monitor UI dashboard | ✅ done | enumerates `MONITOR` actions → host panel + per-app cards (framework badge, UP/DOWN pill, run-chip row) + detail drawer (Runtime block, related actions runnable inline, gate-safe); client-side poll single/5s/30s/1m/5m; theme-aware, textContent-only (012) |
| 025 | App-monitor recipes | ✅ done | `springboot`(actuator + process supplement)/`fastapi`(process + optional `/openapi.json`·`/metrics`)/`generic`(process-only) — discovery-routed via `ss -ltnp`→PID→cmdline classifier, pre-filled `(app-name,port)`, container name recovered from `/proc/<pid>/cgroup`; login-user only (S5) |
| 026 | App-ops recipes | ✅ done | **reserved `app-name` param** correlates ops to app cards (NOT tags/labels, NOT a new recipe class); `SystemdDiscoverer` (`RecipeType.SYSTEMD`) mirrors docker; bounded `tail-logs` + **follow-mode via new run cancellation** (`RunStatus.STOPPED`, `RunService.cancel`, `SshExecutor` channel-close seam, `POST /runs/{id}/cancel`, Stop control); redeploy stays `CUSTOM`/blueprint |
| 027 | Signal-driven machine unreachability | ⚪ todo | **concern** — the going-**OFFLINE**/UNREACHABLE counterpart to 019's instant going-**ONLINE** event: flip faster than the 5-min cron **without flapping** (leaning: a connect-failure triggers an immediate authoritative confirmation probe, not a direct flip). Builds on 019 (PR #30) |
| 028 | Machine name & MCP identity hardening | ⚪ todo | **security** (ARCH **S9**): MCP identifies machines by `id`+user-provided `name`, hides `host`/`port`/`loginUser`; adds a required per-owner-unique **name** at registration; splits the MCP view (`id/name/tags/status`) from the full UI view; `register_machine` still takes host as input but stops echoing it |
| 029 | Fleet monitoring dashboard | ✅ done | fleet view — per-machine sections, tag + app-name filters (filtered-out = unpolled), a synthetic `no-apps` host-only view, per-app **mem-% of host**, unified per-app cards (checks + ops), new read `GET /api/runs/{id}/children`; folds in the spec-025 actuator-liveness → `http app monitor` fallback |
| 030 | Docker container monitoring | 🟢 resolved | **concern** — graduated into specs **032–035** (2026-07); stays the problem framing, the *how* lives there |
| 031 | Deferred follow-ups triage | ⚪ todo | **concern** (options open) — consolidates every deferred implementation note + spec-eval finding across built specs and merged PRs (#38/#39) into one worklist; each item re-asked **keep / drop / already-addressed**. Overlaps but does not replace the H-backlog below (see **H8**) |
| 032 | Monitoring axes foundations | ✅ done | the **consumer contract** for RAM/CPU/disk-as-%-of-host: `MonitorConsumerView` (role/source/dedication/owner/usedBy/bucket) + app-level CPU metric-kind; extends 022; prereq for 033/034. Folds in the **H8** cleanup (single source of truth per axis) |
| 033 | Docker container discovery | ✅ done | docker-native discovery — **compose project = app** (`com.docker.compose.project` label), datastore classification by image, `docker stats`/`ps -s`/`system df -v` metrics, springboot-in-docker shown once. `RecipeType.MONITOR`, gate untouched. **Resolves concern 030**; gated by 035 |
| 034 | Fleet monitor UI/UX redesign | ✅ done | segmented tri-axis machine bars (one colour per consumer), all-three-axes cards, the **databases lens** (Dedicated owner-split / Shared used-by, one lens two bands), hidden docker/system buckets, categorical palette token group. spec-012 idiom; ref [`docs/fleet-resource-mock.html`](../docs/fleet-resource-mock.html). Builds on 029 + 032 |
| 035 | Discovery enablement & UX | ✅ done | **per-family** discovery enablement (Docker/Systemd/Database…), **docker off by default** (socket = root-equivalent); a machine "Discovery" panel. Enablement ≠ the approval gate. **Resolves 030 doubt (1)**; gates 033 |
| 036 | Recipe & param discovery lifecycle | ⚪ todo | **concern** (options open) — lifecycle *beyond approval*: re-discovery adds/refreshes but never **retires** a vanished resource (lingers, incl. runnable APPROVED); no delete/hide/**suppress** (revoke is the only stop); fan-out lists are all-or-nothing and discoverers **flood** (all systemd units / all containers). Keyed on the gate-safety asymmetry: narrowing `APP_PORT_LIST` is gate-free, but the `app-name` ALLOWED_SET is hashed |
| 037 | Docker consumer metric polling | ✅ done | fills the docker axes live — the param-free `docker stats`/`ps -s`/`system df -v` reads parsed client-side and normalized to % of host (RAM ÷ total, CPU ÷ nproc, disk ÷ data-root FS); adds the `nproc` **`cores`** host vital as the CPU denominator. Follow-up to 034 |
| 038 | Compose-project grouping | ✅ done | one card per compose project — a project's datastores render as `services[]` of a single APP consumer (not scattered dedicated cards), and the Databases lens derives its Dedicated band from those services. Fixes the 033 "not composing" display |
| 039 | Native consumer CPU axis | ✅ done | fills the native app CPU axis — `applyConsumerReading` parses the process-tree `%CPU` (÷ host cores, mirroring docker) so native apps show CPU instead of "no data". Disk stays `—` for native (no attributable footprint) |
| 040 | Monitor as a runtime view over runs & model weight | ⚪ todo | **concern** (options open; leaning = **thin BE**) — monitor polls run through `POST /runs` but are invisible (Runs UI is a `localStorage` log; no server run-**list** endpoint), and a classification taxonomy (032 enums, 033/038 grouping) accreted server-side though only discovery-over-SSH + the gate genuinely force it (**S9 does not**). Options: surface runs (B) / thin toward UI-or-BFF assembly (C, the leaning) / backend read-model (D). Revisits 032/033/038 |
| 041 | Host system/other usage segment | ✅ done | on branch `moacyrricardo/spec-041-host-system-usage-segment` (PR #56) — shows **real** RAM/CPU/disk on app-less machines: polls host vitals' **used** (was discarded), renders an **OTHER/system** segment = `host_used − Σ attributed` (clamped ≥0, absent vital ⇒ —) so a bare box no longer looks idle, keeps `total − used` as the hatched free tail. Client-side (spec-040 leaning); needs `monitor machine` vitals. Concrete driver for **040** |
| 042 | Blueprint authoring UI (command-builder form) | ⚪ todo | completes spec-010's UI: today you can create/list/instantiate blueprints but **cannot add actions** (no command-authoring form exists anywhere — `renderCommand` only *displays*), so a UI-authored blueprint is permanently empty & instantiates to zero-action recipes. Build a reusable argToken/paramDef **command-builder** + add/edit-action + edit-blueprint + fix instantiate target selection; reusable by custom-recipe-action authoring |
| 009 | Cloud import (discovery provider) | ⏸ parked | fast-follow after the core |

## Build order

```
spine (serial):   001 → 002 → 011 → 003 → 004
fan-out (parallel on 004):   005 · 006 · 007 · 010
converge:         008   (MCP tool surface)
then:             012 (UI)   ·   009 (cloud, parked)
```

The spine is a hard dependency chain. The fan-out specs depend only on 004/003/011
(not each other), so they build in parallel. 008 needs 005/006/011. 012 renders the
backend, so it builds after it.

**Monitoring + app-ops (specs 021–026 + 029, graduating concern 020):**

```
021 idempotency → 022 foundations → 023 monitor-machine · 024 UI → 025 app-monitors → 026 app-ops → 029 fleet
```

021 is the hard prerequisite (else monitoring shows duplicate app cards). 022 pins
the shared model (classification, the `appName` label convention, the fan-out
`APP_PORT_LIST` run mode, run-row pruning). 023 (host vitals) and 024 (dashboard,
needs ≥1 monitor recipe) then go together; 025 (app-monitor family) and 026 (app-ops
facade, mutating, adds run-cancellation for follow-mode logs) build on 022. 029
(fleet dashboard) lands last, unifying 024's per-action cards into per-app cards
across the whole fleet (tag + app-name filters, per-app mem-% of host).

## Deferred hardening backlog

Findings raised by spec-eval during the builds, deliberately deferred (not
blocking) — candidates for future `todo` specs. The ARCH.md **S1–S8 deferred-risk
register** covers the security posture (auth/transport/host-key/sudo/cloud-creds/
rate-limiting); the items here are correctness/robustness follow-ups.

| # | Finding | From | Priority |
|---|---------|------|----------|
| H1 | `RunOutputHub` never evicts run channels (unbounded memory) and holds the per-channel lock during SSE network I/O (a slow client stalls the SSH thread) — add eviction/TTL + release the lock around I/O | 005 | high |
| ~~H2~~ | ~~Re-running discovery isn't idempotent (duplicate recipes)~~ — **promoted to spec 021** (reconcile by `(machine, type, name)`; refresh unapproved proposals in place, diff APPROVED-differs, uniqueness guard) | 006 | medium |
| H3 | `DiscoveryService.discover` runs SSH probes inside the DB transaction — probe outside the persistence tx | 006 | medium |
| H4 | `DatabaseDiscoverer` backup filename is fixed per engine (overwrites across DBs / repeated runs) — template from the `db` param | 006 | low |
| H5 | Custom-script **content-pinning**: hash the script at approval, verify before each run (path-not-contents trust; escalation risk with sudo) — **promoted to spec 015** | 007 | medium |
| H6 | `ConnectivityCheckJob` probes the whole fleet inside one `@Transactional`; `MinaSshExecutor` builds a fresh SSH client per `exec()` — move to bounded concurrency + a pooled client | 003 | medium |
| H7 | `ActionSnapshot` canonical serialization uses unescaped delimiters (theoretical hash-collision surface; currently moot) | 004 | low |
| H8 | Dead server-side helpers duplicate client logic — `MonitorDtos.opsForApp` (026) and `MonitorDtos.memPctOfHost`/`parseHostMemTotalMb` (029) are unused in production (metrics computed client-side) and only test-called; drop them or wire them so mem-% has a single source of truth (surfaced by spec-eval on PRs #38/#39; also carried as an open item in concern **031**) | 026/029 | low |

**Promoted:** **H1 + H3 + H6 → spec 013** (runtime resource hygiene) — grouped by
their shared root cause (holding a resource across network I/O); ✅ **shipped on
`main`**. **H5 → spec 015** (custom-script content-pinning, ⚪ todo) — a *security*
spec beside the ARCH S-register (posture, not robustness); resolves H5 and hardens
S5. **H2 → spec 021** (discovery idempotency, ✅ done) — the monitoring
prerequisite. **H4 / H7** remain backlog.

**Post-v1 follow-ups:** **016** (graceful shutdown + orphaned-run reconciliation,
from the runtime-lifecycle review) is ✅ **done on `main`**. The one live
content-pinning todo is **015** (the TOCTOU hole; hash-at-approval + re-hash-at-run).
Also `todo`: **027** (a *concern* — signal-driven going-OFFLINE, options still open)
and **028** (an authored *security* spec — machine name & MCP identity hardening,
ARCH S9). **017** is a *concern* (not a decision) weighing the transaction-boundary
strategy behind 013's H3/H6 — its leaning: keep the injected `TransactionTemplate`.

**Resolved (shipped in 008):** MCP actor-propagation. `ScopedValue` is
thread-confined and the MCP SDK dispatches tool handlers off the request thread, so
`CurrentUser.require()` inside a tool would throw — 008 fixed it with
`immediateExecution(true)` (tools run on the token-bound request thread) plus a test
asserting the user resolves inside a tool call.

## The docker-monitoring epic (specs 032–035)

Concern **030** graduated into a four-spec build (the *how* now lives in the specs; 030
keeps the problem framing). Dependency order — 033 and 034 parallelise once 032 lands:

```
032 axes foundations → ( 033 docker discovery  ·  034 monitor UI/UX ) → 035 discovery enablement
```

- **032** pins the shared **consumer contract** (RAM/CPU/disk as % of host; role/source/
  dedication/owner/usedBy/bucket) so 033 (backend) and 034 (frontend) can build in
  parallel; it also folds in the **H8** dead-helper cleanup.
- **033** adds docker-native discovery keyed on the `com.docker.compose.project` label
  (compose project = app), classifies datastores, and takes metrics from `docker stats` /
  `ps -s` / `system df -v`. Gate untouched (`MONITOR` recipes only).
- **034** is the UI/UX redesign — segmented tri-axis bars, the **databases lens**
  (Dedicated owner-split vs Shared used-by), hidden docker/system buckets — in the
  spec-012 idiom; design reference [`docs/fleet-resource-mock.html`](../docs/fleet-resource-mock.html).
- **035** gates *whether* a discoverer may probe: **per-family enablement, docker off by
  default** (the socket is root-equivalent). Enablement is not the approval gate.

Three follow-ups made the docker/native axes actually live (all ✅ done): **037** polls
the docker metric reads into the axes (+ the `nproc` `cores` denominator), **038** groups
a compose project into one card (datastores as `services[]`, feeding the Databases lens),
and **039** fills the native app CPU axis from the process-tree probe. Concern **040**
then steps back to ask how much of this classification should live in the backend at all.

## Open concerns

- **[036](./036-todo-recipe-param-discovery-lifecycle.md) — Recipe & param discovery lifecycle.**
  The lifecycle *after* approval: vanished-resource recipes never retire (they linger,
  including as runnable APPROVED), there is no delete/hide/suppress (revoke is the only
  stop), and fan-out lists are all-or-nothing while discoverers enumerate broadly.
  Options span retire-vs-mark-stale, an ignore/suppress list, per-item curation, and
  discoverer scoping — turning on the gate-safety asymmetry (narrowing is free; the
  hashed `app-name` ALLOWED_SET is not). Follows the 032–035 epic; grounded in code.

- **[031](./031-todo-deferred-followups-triage.md) — Deferred follow-ups triage.**
  A single worklist of every deferred note and spec-eval finding across the built
  specs and merged PRs, each re-asked **keep / drop / already-addressed**. It is a
  triage index, not a work spec: resolving it spins the *keep* items into their own
  specs (or folds them into the H-backlog above).
