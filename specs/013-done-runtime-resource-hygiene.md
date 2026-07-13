# 013 — Runtime resource hygiene (streaming eviction, transaction scoping, SSH pooling)

> **Status: done.** Branch `moacyrricardo/spec-013-runtime-resource-hygiene`
> (Linear blocked for this repo, so no issue id). Implemented as specified across
> `run` / `discovery` / `machine` / `ssh`. Deltas from the spec:
> - **H1 delivery executor** is a cached daemon thread pool (threads exist only
>   while a subscriber is actively draining, so there is no parked thread per SSE
>   connection) with a `@PreDestroy` shutdown; backpressure is enforced per drain
>   pass, so a burst that outruns delivery drops even a willing subscriber — the
>   bounded-buffer-then-disconnect policy the spec chose.
> - **H6 `MinaSshExecutor`** starts its single shared `SshClient` **lazily on first
>   use** (still once, verifier set once before `start()`), not eagerly in the
>   constructor — so a deployment (or test) that never issues an SSH command starts
>   no MINA IO threads. An `@Autowired` marks the production constructor since a
>   second package-private client-factory seam constructor now exists.
> - The **`SshClient`-reused-once** assertion lives in `MinaSshExecutorTest` (its
>   natural home, a pure Mockito unit test) rather than `ConnectivityCheckJobTest`,
>   which drives the job through a fake `SshExecutor`; `ConnectivityCheckJobTest`
>   carries `slowProbe_DoesNotBlockOthers`.
> - Test-isolation: the `NOT_SUPPORTED` probe test in `DiscoveryServiceTest` commits
>   into the shared in-memory DB (including the `@BeforeEach` `alice`), so it
>   registers under a unique owner and deletes every committed row (owner, machine,
>   recipes, actions, and `alice`) in a `finally`; `ConnectivityCheckJobTest`'s slow
>   test likewise cleans up its committed machines + owner.

## Context

During the 003/005/006 builds, spec-eval flagged three related runtime-robustness
gaps (H1, H3, H6 in the [catalog backlog](./catalog.md)). They share one root cause:
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
   transactions (not one fleet-wide `@Transactional`), and reuse a single pooled
   SSH client instead of building one per `exec()`.

## Implementation

**H1 — `RunOutputHub` (`run` module).**

- **Delivery model — shared history + per-subscriber cursor (no I/O under the
  lock).** Keep the single append-only `history` list per channel; give each
  subscriber a **cursor** into it. `publish`/`complete` mutate `history` /
  `complete` / `completedAt` and snapshot the delivery set **under the monitor**,
  then wake deliveries and call `onEvent` / `onComplete` **outside** the monitor.
  A slow or backpressured client (SSE `eventSink.send`, or the MCP
  `progressNotification` of 008) therefore can never stall the run's producer
  thread or the other subscribers. This model preserves the "buffered prefix then
  live tail, no lost chunk, no duplication" invariant **by construction** — a
  mid-stream subscriber is just a cursor starting at the current history size;
  there is no separate replay-buffer-then-switch-to-live seam to make atomic.
  Deliver via a small dedicated executor (a per-delivery "scheduled" flag,
  re-submitted on new data / on complete) so there isn't one parked thread per SSE
  connection.
  - *Producer threading note:* per run, `publish` is single-producer — stdout/stderr
    chunks arrive on MINA's session-read thread and the terminal `complete` runs on
    the run-worker thread only after `channel.waitFor(CLOSED)`, so the last publish
    happens-before complete. The redesign must still provide cross-thread visibility
    of `history`/`complete` (read them under the same monitor, or make them
    concurrent/`volatile`).
  - **Backpressure / drop policy:** cap how far a delivery may lag
    (`ca.run.output-subscriber-backlog`); a subscriber past the cap is dropped and
    its `eventSink` closed — bounded buffer then disconnect, never block the
    producer.

- **Eviction — scheduled reaper with a testable seam.** When a run reaches
  `DONE`/`FAILED`, record `completedAt` and retain the channel + history. A
  `RunOutputEvictionJob` (`@Scheduled`) calls `hub.evict(Instant asOf)`; the
  `asOf` parameter is the seam a unit test drives directly (no `Thread.sleep`).
  `evict` removes channels where `complete && completedAt <= asOf - retention`
  (`ca.run.output-retention`, default `10m`) and enforces an LRU-by-`completedAt`
  cap (`ca.run.output-max-channels`) — it **only ever evicts complete channels**,
  never a live run, and never nulls `history` (a still-draining delivery holds a
  strong reference, so removing the map entry is GC-safe).

- **Subscribe predicate — stop resurrecting evicted channels (the evict/subscribe
  race).** Today `RunOutputHub.subscribe` uses `computeIfAbsent`, so subscribing to
  an already-finished, evicted run would create an empty channel and hang forever
  (no further `publish`/`complete`). Fix it in `RunService.subscribeToOutput`:
  ```
  Run run = requireRun(id);                       // owner-scoped 404 first
  if (run terminal /* DONE|FAILED */) {
      if (!hub.attachIfPresent(id, subscriber))   // get-without-create; replays history(+exit), attaches or completes
          replayPersisted(run, subscriber);       // evicted → synthesize stdout/stderr/exit from the persisted Run, then onComplete
  } else {
      hub.subscribe(id, subscriber);              // QUEUED/RUNNING: computeIfAbsent create + attach (live path)
  }
  ```
  `attachIfPresent` does a get-without-create and runs its get-check-attach under
  the channel monitor; `evict` removes under the same discipline, so an attach that
  finds no channel returns `false` and the caller replays from the persisted,
  append-only `Run.stdout/stderr` — which is exactly what a live replay would have
  produced. `RunOutputResource` (MCP `run://{id}/output`) already reads persisted
  output, so it is unaffected.

**H3 — `DiscoveryService` (`discovery` module).**
- Split `discover(machineId)`: `requireMachine` (a read, no long tx), then run all
  `RecipeDiscoverer` SSH probes with **no open transaction**, collecting proposals
  in memory, then persist them in a single short transaction.
- **Avoid the self-invocation trap.** A private `@Transactional persist(...)` called
  from `discover()` on the same bean is a **no-op** — Spring's proxy only intercepts
  external calls, so no transaction starts and the nested `RecipeService`/
  `ActionService`/`ApprovalService` writes each open their own tiny tx (no
  atomicity). Use an injected **`TransactionTemplate`** (via `PlatformTransactionManager`)
  around the persist block so those `REQUIRED` writes join one short tx; a separate
  `@Transactional` collaborator bean is the acceptable alternative. Not a bare
  annotated private method.
- Detached-entity use across the no-tx probe phase is safe: discoverers touch only
  scalar getters (`host`/`port`/`loginUser` via `Probes.target`); `Machine.tags` is
  EAGER and `owner` is never traversed, so no `LazyInitializationException`.
  Proposals still land `PENDING_APPROVAL`; the box is still never mutated.

**H6 — `ConnectivityCheckJob` + `MinaSshExecutor` (`machine` / `ssh` modules).**
- `ConnectivityCheckJob`: drop the fleet-wide `@Transactional`. Snapshot the fleet
  (`findAll()` → `(id, target, currentStatus)`), probe each machine **outside** a
  transaction with **bounded concurrency** (a small fixed pool sized by
  `ca.connectivity.concurrency`, each probe bounded by the existing connect/exec
  timeouts), then apply each status change **on the job thread** in its own short
  transaction (`TransactionTemplate` / `@Transactional` collaborator), re-loading and
  comparing **inside** that tx so "unchanged status ⇒ no new revision" (spec-003)
  still holds.
  - *`via = SYSTEM` correctness:* the status write must run on the job thread, where
    `CurrentUser` is unbound, so the Envers listener stamps `userId = null`,
    `via = SYSTEM`. The probe pool threads do **no** DB/audit work — and note
    `CurrentUser` is a `ScopedValue`, thread-confined, so it does **not** propagate
    into the probe threads anyway (a non-issue precisely because they're DB-free).
- `MinaSshExecutor`: manage a **single shared `SshClient`** as a bean — started once,
  accept-all verifier (S3) set **once** at init, `stop()` in `@PreDestroy` — instead
  of `setUpDefaultClient()` + `start()`/`stop()` per `exec()`. Each `exec()` opens
  only the per-command `ClientSession` + `ChannelExec` (the per-session
  `addPublicKeyIdentity` stays per-call — it's session state, not client
  reconfiguration). Apache MINA SSHD 2.14.0 explicitly supports one `SshClient`
  reused across many concurrent sessions provided it isn't reconfigured after
  `start()`, so a single shared client — **not** a client pool — is correct. S3
  accept-all verifier, argv single-quoting (S4), and `sudo -n` (S5) behavior stay
  exactly as-is.

**Config** (`ca.*`, constructor `@Value` with in-code defaults):
- New: `ca.run.output-retention` (default `10m`), `ca.run.output-max-channels`,
  `ca.run.output-subscriber-backlog`, `ca.connectivity.concurrency`.
- Reuse (already defined on `MinaSshExecutor`): `ca.ssh.connect-timeout-seconds`,
  `ca.ssh.exec-timeout-seconds` — the probe pool uses these, no new key.

**Tests.**
- `RunOutputHubTest` (new, plain POJO unit test):
  - channel evicted after TTL / cap via the `evict(asOf)` seam; a live channel is
    never evicted;
  - a **blocked subscriber neither stalls the producer nor other subscribers**, and
    is dropped after the backlog cap (the direct regression test for the stall bug).
- `RunServiceTest` (extend): after a happy-path run reaches terminal, force
  `hub.evict(...)`, then subscribe and assert the subscriber replays the **persisted**
  stdout + exit + `onComplete` (the fallback); a `QUEUED` run attaches live.
- `DiscoveryServiceTest` (extend): the fake `SshExecutor` records
  `TransactionSynchronizationManager.isActualTransactionActive()` per `exec`; a new
  method (marked `@Transactional(propagation = NOT_SUPPORTED)`) asserts it is
  `false` during probes; proposals still `PENDING_APPROVAL`.
- `ConnectivityCheckJobTest` (refactor): the job constructor gains
  `(…, PlatformTransactionManager, int concurrency)`; drop the outer `tx.execute`
  wrapper (else the job's own per-machine tx just joins it) and run `checkAll()`
  inside `CurrentUser.runWhere(AuthContext.system(), …)` so the write commits (class
  is `NOT_SUPPORTED`), stamps `via = SYSTEM`, and the "unchanged ⇒ no revision"
  assertion still holds. Add `slowProbe_DoesNotBlockOthers` (bounded concurrency) and
  an `SshClient`-reuse assertion (`start()` called once across many `exec()`s, via an
  `SshClient`-provider seam).

**Test-isolation hazard (must respect).** The test DB is one shared in-memory
instance (`jdbc:h2:mem:…;DB_CLOSE_DELAY=-1`), so a `TransactionTemplate` running
under a `NOT_SUPPORTED` method **commits** and its rows survive into later test
methods and classes. Contain it: opt only the strictly-necessary methods out of the
rollback wrapper, register under a per-test unique-email owner (so owner-scoped reads
elsewhere can't see leaked rows), and clean up committed rows in `@AfterEach` where a
`findAll`-style test (e.g. `ConnectivityCheckJobTest`) could otherwise observe them.

## Known Gaps

- Does **not** add global run rate-limiting / quota (that's S7) — separate.
- Streaming backpressure policy is fixed here: bounded per-subscriber lag then
  disconnect (`ca.run.output-subscriber-backlog`).
- MINA client thread-safety is **resolved** to a single shared client (see H6); the
  small-pool-of-clients fallback is retained only as a contingency if a future MINA
  version regresses concurrent-session safety.

## Sequencing

Independent of 009 (parked) and any UI work; touches `run` / `discovery` /
`machine` / `ssh` — all merged on `main`. Suggested build order: (1) H1 hub +
eviction + fallback (self-contained in `run`), (2) H3 discovery tx scoping, (3) H6
connectivity + shared `SshClient` — H3 and H6 share the `TransactionTemplate`-around-
I/O pattern, so do one and mirror it. A good first post-v1 hardening pass.
