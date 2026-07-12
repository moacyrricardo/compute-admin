# Spec catalog

The architectural decision record for compute-admin. Each feature lands as a
numbered spec (`NNN-status-slug.md`, status `todo`/`doing`/`done` by file rename),
authored with `/new-spec` and grounded in [ARCH.md](../ARCH.md) (whose "Code
conventions" section is the build charter, mirroring `birthday-rsvp`). Linear is
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
| 020 | Machine monitoring | 🟢 graduated | **umbrella concern of record** — resolved into specs 021–026 (build order below); keeps the problem framing + the Q1/Q2 decisions |
| 021 | Discovery idempotency | ✅ done | resolves **H2** — re-discovery reconciles by `(machine, type, name)` instead of duplicating; refresh DRAFT/PENDING proposals in place, surface a diff on APPROVED-differs; uniqueness guard. **Monitoring prerequisite (build first)** |
| 022 | Monitoring foundations | ✅ done | the decisions spec — `RecipeType.MONITOR` (display-only, gate unchanged); `appName`(+`runtime`) label convention + double-detection link; `APP_PORT_LIST` param + **fan-out run mode** (S4-safe: fixed template per item, never a shell loop); run-row pruning (extends 013 eviction) |
| 023 | `monitor machine` recipe | ⚪ todo | universal read-only host vitals — cpu (`top -bn1`), ram+swap (`free -m`), disk (`df -h`); auto-proposed on every reachable box; no app param (→ host panel) |
| 024 | Monitor UI dashboard | ⚪ todo | enumerates `MONITOR` actions → host panel + per-app cards (framework badge, UP/DOWN pill, run-chip row) + detail drawer (Runtime block, related actions runnable inline, gate-safe); client-side poll single/5s/30s/1m/5m; theme-aware, textContent-only (012) |
| 025 | App-monitor recipes | ⚪ todo | `springboot`(actuator + process supplement)/`fastapi`(process + optional `/openapi.json`·`/metrics`)/`generic`(process-only) — discovery-routed via `ss -ltnp`→PID→cmdline classifier, pre-filled `(app-name,port)`, container name recovered from `/proc/<pid>/cgroup`; login-user only (S5) |
| 026 | App-ops recipes | ⚪ todo | `appName`+`opKind` label **facade** over existing runtime recipes (docker/systemd/custom) — NOT a new recipe class; optional `SystemdDiscoverer`; bounded `tail-logs` fits today, **follow mode (`-f`) blocked on a new run-cancellation engine addition** (spec'd here); redeploy stays `CUSTOM`/blueprint |
| 027 | Signal-driven machine unreachability | ⚪ todo | **concern** — the going-**OFFLINE**/UNREACHABLE counterpart to 019's instant going-**ONLINE** event: flip faster than the 5-min cron **without flapping** (leaning: a connect-failure triggers an immediate authoritative confirmation probe, not a direct flip). Builds on 019 (PR #30) |
| 028 | Machine name & MCP identity hardening | ⚪ todo | **security** (ARCH **S9**): MCP identifies machines by `id`+user-provided `name`, hides `host`/`port`/`loginUser`; adds a required per-owner-unique **name** at registration; splits the MCP view (`id/name/tags/status`) from the full UI view; `register_machine` still takes host as input but stops echoing it |
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

**Monitoring + app-ops (specs 021–026, graduating concern 020):**

```
021 idempotency → 022 foundations → 023 monitor-machine · 024 UI → 025 app-monitors → 026 app-ops
```

021 is the hard prerequisite (else monitoring shows duplicate app cards). 022 pins
the shared model (classification, the `appName` label convention, the fan-out
`APP_PORT_LIST` run mode, run-row pruning). 023 (host vitals) and 024 (dashboard,
needs ≥1 monitor recipe) then go together; 025 (app-monitor family) and 026 (app-ops
facade, mutating, adds run-cancellation for follow-mode logs) build on 022.

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

**Promoted:** **H1 + H3 + H6 → spec 013** (runtime resource hygiene) — grouped by
their shared root cause (holding a resource across network I/O); ✅ **shipped on
`main`**. **H5 → spec 015** (custom-script content-pinning, ⚪ todo) — a *security*
spec beside the ARCH S-register (posture, not robustness); resolves H5 and hardens
S5. **H2 → spec 021** (discovery idempotency, ✅ done) — the monitoring
prerequisite. **H4 / H7** remain backlog.

**Post-v1 follow-ups (authored, awaiting build):** **015** (content-pinning, the
one live TOCTOU hole) and **016** (graceful shutdown + orphaned-run reconciliation,
from the runtime-lifecycle review) are `todo` specs ready to build. **017** is a
*concern* (not a decision) weighing the transaction-boundary strategy behind 013's
H3/H6 — its leaning: keep the injected `TransactionTemplate`.

**Resolved (shipped in 008):** MCP actor-propagation. `ScopedValue` is
thread-confined and the MCP SDK dispatches tool handlers off the request thread, so
`CurrentUser.require()` inside a tool would throw — 008 fixed it with
`immediateExecution(true)` (tools run on the token-bound request thread) plus a test
asserting the user resolves inside a tool call.
