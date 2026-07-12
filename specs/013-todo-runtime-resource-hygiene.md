# 013 — Runtime resource hygiene (streaming eviction, transaction scoping, SSH pooling)

## Context

During the 003/005/006 builds, spec-eval flagged three related runtime-robustness
gaps (H1, H3, H6 in the [catalog backlog](./README.md)). They share one root cause:
**holding a resource — memory, a DB transaction, or an un-pooled SSH client —
across slow network I/O.** Each was deferred (none breaks the invariant), but
together they're the reliability issues that bite once the app runs for a while or
manages more than a handful of machines. This spec fixes the three as one coherent
pass over the run / discovery / connectivity paths.

Out of scope (stay in the backlog): H2 (discovery idempotency), H4 (backup
filename), H7 (hash-serialization). H5 (custom-script content-pinning) is security
posture — it belongs in a separate security spec beside the ARCH S-register, not
here.

## Decision

Bound and release runtime resources around network I/O:
1. **RunOutputHub** — evict finished-run channels (TTL + cap); never hold the
   per-channel lock during SSE/MCP output delivery.
2. **Discovery** — run SSH probes **outside** the persistence transaction; persist
   proposals in a short transaction afterward.
3. **Connectivity + SSH** — probe with bounded concurrency in short per-machine
   transactions (not one fleet-wide `@Transactional`), and reuse a pooled SSH
   client instead of building one per `exec()`.

## Implementation

**H1 — `RunOutputHub` (`run` module).**
- **Eviction:** when a run reaches `DONE`/`FAILED`, retain its channel + buffered
  output for a bounded TTL (`ca.run.output-retention`, default `10m`) or a
  max-channels LRU cap (`ca.run.output-max-channels`), then evict. Late
  `get_run` / SSE subscribers after eviction fall back to the persisted
  `Run.stdout/stderr` (already stored, append-only). Reap via a small
  `RunOutputEvictionJob` (scheduled) or a lazy check on access.
- **No I/O under the lock:** snapshot the subscriber list + buffered chunks under
  the per-channel monitor, then invoke `SseEventSink.send` / MCP progress
  **outside** the lock (copy-then-send), so a slow or blocked client can't stall
  the SSH streaming thread. Give each subscriber a bounded queue; a subscriber that
  can't keep up is dropped/disconnected rather than blocking the producer.

**H3 — `DiscoveryService` (`discovery` module).**
- Split `discover(machineId)`: run all `RecipeDiscoverer` SSH probes with **no open
  transaction**, collect the proposals in memory, then persist them in a single
  short `@Transactional` step. Remove the transaction scope that currently spans the
  probes. Proposals still land `PENDING_APPROVAL`; the box is still never mutated.

**H6 — `ConnectivityCheckJob` + `MinaSshExecutor` (`machine` / `ssh` modules).**
- `ConnectivityCheckJob`: drop the fleet-wide `@Transactional`. Probe each machine
  **outside** a transaction with **bounded concurrency** (a small fixed pool +
  per-machine connect/exec timeout via the existing async executor), then persist
  each status update in its own short transaction. Audit `via = SYSTEM` unchanged.
- `MinaSshExecutor`: manage a **shared/pooled `SshClient`** as a bean (started once,
  stopped on shutdown) instead of `setUpDefaultClient()` + `start()`/`stop()` per
  `exec()`; open/close only the per-command session/channel. S3 accept-all
  verifier, argv binding, and `sudo -n` behavior stay exactly as-is.

**Config** (`ca.*`, constructor `@Value` with in-code defaults):
`ca.run.output-retention`, `ca.run.output-max-channels`,
`ca.connectivity.concurrency`, connect/exec timeouts.

**Tests.**
- `RunOutputHubTest` — channel evicted after TTL/cap; a late read falls back to the
  persisted output; a blocked subscriber neither stalls the producer nor other
  subscribers.
- `DiscoveryServiceTest` (extend) — probes run with no open transaction (persistence
  happens after); proposals still `PENDING_APPROVAL`.
- `ConnectivityCheckJobTest` (extend) — statuses updated in separate per-machine
  transactions; a slow/failing probe on one machine doesn't block the others
  (bounded concurrency); the SSH client is reused across `exec()` calls.

## Known Gaps

- Does **not** add global run rate-limiting / quota (that's S7) — separate.
- SSH pooling assumes MINA client thread-safety for concurrent sessions; if it
  isn't, use a small pool of clients rather than one shared instance (decide at
  build time).
- Streaming backpressure policy (drop vs disconnect a slow client) is pinned during
  build; default to a bounded buffer then disconnect.

## Sequencing

Independent of 009 (parked) and any UI work; touches `run` / `discovery` /
`machine` / `ssh` — all merged on `main`. A good first post-v1 hardening pass.
