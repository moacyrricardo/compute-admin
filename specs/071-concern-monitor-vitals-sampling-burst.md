# 071 — Monitor vitals sampling connection burst

## Problem

The monitor page renders **no CPU / memory / disk** against a real, rate-limited host.
The cause is **not** discovery (that is [070](./070-done-ssh-session-reuse.md)) but the
**run/poll path** the monitor UI uses to sample vitals — a distinct, higher-cadence
connection burst that `070`'s per-discovery `withSession` scope cannot touch.

**Mechanism (verified in code).** Each monitor poll cycle, per machine, the UI fires:

- `app.js:2052` — `Promise.all([pollHostTotal, pollHostCores, pollHostDiskTotal,
  pollHostCpuUsed])` → **4 concurrent** host-vitals runs;
- `app.js:2070–2074` — then `Promise.all([pollConsumers, pollDockerConsumers])`, where
  `pollConsumers` drives the `APP_PORT_LIST` fan-out.

Every poll POSTs a **run**, and `RunService` (`run/service/RunService.java:202–268`)
dispatches **one child run per `APP_PORT_LIST` item** onto the bounded `runExecutor`
pool (core 2 / max 8, `AsyncConfig`), each child an independent `execStreaming` →
independent TCP + auth. With ~7 apps that is **10–15 near-simultaneous connections per
machine per cycle**, at the UI's shipped **5s / 30s** cadences (`MONITOR_CADENCES`,
`app.js:1495`).

That sustained churn — not one-shot discovery — is the "~2 connections/second" seen in
the live log, and it is what trips `MaxStartups` / fail2ban: each refused connect lands
as a **FAILED run → a blank axis**, and the resulting host-side throttling plausibly also
refuses discovery's probes (the interaction noted in 070). **Fixing 070 alone leaves the
monitor broken**, and its churn may keep re-breaking discovery.

The `runExecutor` fan-out and `RunService.execStreaming` are the same code that runs
*approved actions*. This is a **run-model** decision, not a UI tweak, and it must not
touch the approval gate.

## Hypotheses / Options

- **A — Batched host-vitals probe.** Replace the 4 separate host runs
  (`pollHostTotal/Cores/DiskTotal/CpuUsed`) with **one** run whose fixed script emits all
  four values, executed over a single `withSession` (reusing 070's seam). Collapses the
  host-vitals part of every cycle from 4 connections to 1. Smallest, gate-neutral change;
  does not touch the consumer fan-out.
- **B — Shared session across a poll cycle.** Give the poll cycle (host + consumer
  fan-out) **one** authenticated session and run all its probes as channels on it — the
  cross-operation analogue of 070's per-discovery scope. Requires the run/poll path to
  carry a session handle across independently-dispatched `runExecutor` tasks, or to stop
  dispatching vitals sampling as N separate runs. Biggest reduction; most plumbing.
- **C — L2 per-machine connection pool.** A warm, kept-alive session per machine that all
  run/poll/connectivity paths borrow. Fixes this and every future burst, but owns
  keep-alive, idle eviction, health checks, reconnect backoff, stale-session detection,
  and thread-safety (concurrent borrowers respecting remote `MaxSessions`). The heaviest;
  the "sub-minute cadence across many hosts" trigger 070 named for L2 is met **here**.
- **D — Throttle / serialize sampling.** Bound the sampler's concurrency (a small
  per-machine semaphore) and/or lengthen the default cadence so the burst never exceeds
  what the host tolerates. Cheapest; a mitigation, not a cure — leaves the per-cycle
  handshake count high, just spread out.
- **E — Separate vitals sampling from the run model entirely.** Sample host/app vitals on
  a server-side scheduler (not UI-triggered runs), decoupled from `runExecutor`, so the
  monitor reads persisted samples (matching `MonitorService`'s already read-only shape).
  Cleanest conceptually; largest surface (a new sampling subsystem + persistence).

Not in scope for any option: changing what the approval gate covers. Vitals probes are
`MONITOR`-type reads; the gate stays exactly as is.

## Open Questions

- Is the **host-vitals** burst (4 fixed reads) worth fixing on its own (A) ahead of the
  harder **consumer fan-out**, or should both move together (B/E)?
- Does the consumer fan-out genuinely need **one run per app** (spec-022's per-item child
  records, labelled by `appName`), or can a monitor-only sampling path batch items into
  one connection while preserving the per-app display? What does that cost the run-history
  / child-aggregation model?
- If sampling moves off the run model (E), what persists the samples, at what retention,
  and does the monitor UI keep its client-side cadence selector or follow a server tick?
- Should this reuse **070's `withSession` seam** (build 070 first, then A/B on top) or
  supersede it with C? Recommended sequencing: ship **070** (discovery), then decide A–E
  here with 070's seam available.
- Interaction with fail2ban/`MaxStartups`: do we also need **client-side** connect
  backoff so a throttled host is not hammered while banned?
