# 019 — Event-driven machine connectivity status

## Context

`Machine.status` is written in exactly **one** place today —
`ConnectivityCheckJob.checkAll()` on a 5-minute cron (`machine/job/ConnectivityCheckJob.java:103`).
Nothing else updates it: a successful **discovery** or **run** proves the box is
reachable (it just SSHed in), yet the status pill keeps whatever the last sweep set —
so after a working discover the machine can still read `UNREACHABLE` until the next
cron tick, and there is **no manual "test connection"** (the Register screen's "test
the connection" text was never implemented). Connectivity truth is coupled to a single
timer.

## Decision

Decouple "we just reached this machine" from the timer with a **broadcast domain
event + async listener** — the first domain event in the codebase (only Spring's
`ApplicationReadyEvent` is used today, by spec 016).

- **Publish `MachineReachedEvent(machineId, at)`** whenever an SSH interaction against a
  machine **succeeds**: a run whose command executed, a discovery probe that connected,
  the connectivity probe when it gets a result, and a new manual test. Publish from the
  service call sites that hold the `Machine` (they know the `machineId`; the low-level
  `SshExecutor` only sees host/port), via Spring's `ApplicationEventPublisher`. A sibling
  `MachineUnreachableEvent` may carry the failure case, or the periodic job keeps owning
  the ONLINE→OFFLINE/UNREACHABLE transition — decided in Implementation.
- **An async listener updates status.** A `@TransactionalEventListener(phase = AFTER_COMMIT)`
  (so it fires only once the triggering work committed) — or a plain `@Async @EventListener` —
  marks the machine `ONLINE` + `updatedAt` in its **own short transaction**, on a pool
  thread where `CurrentUser` is unbound so the Envers revision stamps `via = SYSTEM`
  (identical to the job). It is **idempotent / write-on-change only**: no revision when
  the status is already `ONLINE` (preserving spec-003's "a liveness signal is not a
  config edit" rule).
- **Manual "Test connection"** — `POST /api/machines/{id}/test` runs the trivial probe
  now and publishes the event, giving the operator an on-demand refresh and finally
  wiring the Register screen's promised button.
- **The periodic job stays** — it remains the authority for detecting a machine going
  *away* (ONLINE → OFFLINE/UNREACHABLE) on its cron; the event path adds *immediate*
  ONLINE feedback from real usage. The two are complementary.

## Options considered

- **(A) Event + async listener (chosen).** Decoupled: any number of listeners can react
  to "machine reached" without the SSH callers knowing about them — notably spec 018's
  facts-probe/auto-tagger subscribes to the same event. Introduces one lightweight domain
  event (Spring's built-in publisher; no external bus).
- **(B) Direct `machineService.markReachable(id)` inline** at each SSH success site.
  Simpler and no new pattern, but couples every SSH caller to status logic, repeats it in
  three places, and gives 018 nowhere clean to hook. Rejected per the product direction
  and because 018 wants the same signal.

## Implementation

- New `machine/event/MachineReachedEvent` (record: `machineId`, `Instant at`) and an
  `ApplicationEventPublisher` injected into `RunService` (publish after a run's SSH
  channel is established / on non-error completion), `DiscoveryService` (after the probe
  phase connects), and `ConnectivityCheckJob` (on an ONLINE result — or the job keeps
  writing directly; avoid double-writes).
- `machine/event/MachineStatusUpdater` `@TransactionalEventListener(AFTER_COMMIT)` (async):
  re-load the machine, set `ONLINE` + `updatedAt` only if changed, save in a short tx.
  Reuse spec-013's `TransactionTemplate`/short-tx discipline; run on a bounded pool.
- `MachineRS.test(id)` → `POST /machines/{id}/test`: owner-scoped (`requireMachine`),
  runs the probe, publishes the event (or returns the immediate result), 200 with the
  fresh status. UI "Test connection" button on the machine + register screens.
- Guard against double-writes with the periodic job (both may set ONLINE — fine, since
  write-on-change makes the second a no-op).
- **Tests:** publishing `MachineReachedEvent` flips a stale `UNREACHABLE` machine to
  `ONLINE` via the listener; an already-`ONLINE` machine produces **no** new Envers
  revision (idempotence); `via = SYSTEM` on the event-driven update; `POST /machines/{id}/test`
  is owner-scoped (404 for another user's machine) and refreshes status.

## Known Gaps

- The listener only ever sets `ONLINE`; going *offline* still relies on the periodic job
  (a machine that dies is detected within one cron interval). Acceptable — the event path
  is for immediate positive feedback.
- Introduces the first domain-event; kept minimal (in-JVM Spring events, no message bus).
  If more events accrue, revisit a small `event` package convention.
- No debounce: a burst of runs against one machine emits several events; the write-on-change
  listener collapses them to at most one revision, so this is benign.

## Sequencing

Independent to build; **spec 018** composes on top (its facts-probe/auto-tagger subscribes
to `MachineReachedEvent`). Touches `machine` / `run` / `discovery`. A good early win — it
directly removes the "reachable machine shows UNREACHABLE" confusion seen in live testing.
