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
| 013 | Runtime resource hygiene (H1/H3/H6) | ⚪ todo | streaming eviction, tx scoping, SSH pooling |
| 014 | Email + password authentication | ✅ done | replaces Google sign-in; supersedes 011's auth mechanism |
| 016 | Graceful shutdown & run reconciliation | ⚪ todo | drain in-flight runs + boot reconciler for orphaned QUEUED/RUNNING rows; neighbor to S7 (out of scope) |
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

## Deferred hardening backlog

Findings raised by spec-eval during the builds, deliberately deferred (not
blocking) — candidates for future `todo` specs. The ARCH.md **S1–S8 deferred-risk
register** covers the security posture (auth/transport/host-key/sudo/cloud-creds/
rate-limiting); the items here are correctness/robustness follow-ups.

| # | Finding | From | Priority |
|---|---------|------|----------|
| H1 | `RunOutputHub` never evicts run channels (unbounded memory) and holds the per-channel lock during SSE network I/O (a slow client stalls the SSH thread) — add eviction/TTL + release the lock around I/O | 005 | high |
| H2 | Re-running discovery isn't idempotent (duplicate recipes) — define dedup/replace/merge semantics | 006 | medium |
| H3 | `DiscoveryService.discover` runs SSH probes inside the DB transaction — probe outside the persistence tx | 006 | medium |
| H4 | `DatabaseDiscoverer` backup filename is fixed per engine (overwrites across DBs / repeated runs) — template from the `db` param | 006 | low |
| H5 | Custom-script **content-pinning**: hash the script at approval, verify before each run (path-not-contents trust; escalation risk with sudo) | 007 | medium |
| H6 | `ConnectivityCheckJob` probes the whole fleet inside one `@Transactional`; `MinaSshExecutor` builds a fresh SSH client per `exec()` — move to bounded concurrency + a pooled client | 003 | medium |
| H7 | `ActionSnapshot` canonical serialization uses unescaped delimiters (theoretical hash-collision surface; currently moot) | 004 | low |

**Promoted:** **H1 + H3 + H6 → spec 013** (runtime resource hygiene) — grouped by
their shared root cause (holding a resource across network I/O). **H5** →
a future *security* spec beside the ARCH S-register (it's posture, not robustness).
**H2 / H4 / H7** remain backlog.

**Resolved (shipped in 008):** MCP actor-propagation. `ScopedValue` is
thread-confined and the MCP SDK dispatches tool handlers off the request thread, so
`CurrentUser.require()` inside a tool would throw — 008 fixed it with
`immediateExecution(true)` (tools run on the token-bound request thread) plus a test
asserting the user resolves inside a tool call.
