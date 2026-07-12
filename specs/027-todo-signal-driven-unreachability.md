# 027 — Signal-driven machine unreachability

> **Concern (exploratory — options open, no decision yet).** Explores how a machine
> can flip to `OFFLINE`/`UNREACHABLE` *faster* than the 5-minute connectivity cron
> **without flapping**. It is the going-away counterpart to spec-019's instant
> going-online event. Linear is **blocked** for this repo, so this carries no issue
> id; commits use `spec-027` subjects. If it graduates to a decision it becomes a
> spec (and this file gets a WARNING pointing at it). Builds on **spec-019**, which
> must be merged first (PR #30).

## Problem

Spec-019 made connectivity **asymmetric on purpose**. A successful SSH interaction
(run, discovery probe, connectivity probe, or the manual test) publishes a
`MachineReachedEvent`, and `MachineStatusUpdater` flips the machine to `ONLINE`
*immediately*, off the cron cadence
(`machine/event/MachineStatusUpdater.java:54-66`). But the reverse transition —
`ONLINE → OFFLINE/UNREACHABLE` — was **deliberately left to the 5-minute
`ConnectivityCheckJob`** (`019` §"Decision" lines 41-43; `019` "Known Gaps"
lines 78-80; `machine/job/ConnectivityCheckJob.java:46-54`). The event carries only
the positive case; `MachineUnreachableEvent` was consciously **not** added
(`019` "Implementation outcome" lines 109-114).

The consequence is a real, one-sided staleness window: **a machine that has just
gone down keeps showing `ONLINE` for up to ~5 minutes** (until the next cron tick at
`ConnectivityCheckJob.java:84`, `cron = 0 */5 * * * *`), even while runs against it
are already failing to connect. Going-online is instant; going-away lags a full cron
interval.

**Why the naive symmetric fix is wrong.** The obvious move — a mirror
`MachineUnreachableEvent` whose listener flips `UNREACHABLE` on the *first* failed
SSH — **flaps**. A single connect failure is not the same as "the box is down":
transient packet loss, a momentarily saturated host, a brief sshd restart, or an
auth hiccup all produce one-off failures against a perfectly healthy machine. A
first-failure flip turns those into false `UNREACHABLE` pills (and, worse, immediate
audit churn), then the next run or the next cron probe flips it back — visible
oscillation. Going-online can be first-signal because a success is *proof*;
going-away cannot, because a single failure is only *suspicion*. That asymmetry is
the whole design problem: **get down-detection faster without letting a single
failure decide.**

### Two distinctions that must be explicit

1. **Reachability failure vs. command failure — only the former is a status
   signal.** These are already distinct in the code and must stay distinct:
   - A **connect/auth/channel failure** surfaces as an unchecked
     `SshExecutionException` (`ssh/SshExecutionException.java:11-15`, "failure to
     connect, authenticate, or run a command over SSH"). This *is* a reachability
     signal. `ConnectivityCheckJob.probe` already treats it so — it catches the
     `RuntimeException` and maps it to `UNREACHABLE`
     (`ConnectivityCheckJob.java:130-137`), and `RunService.execute` catches it,
     fails the run, and **publishes nothing** (`run/service/RunService.java:236-248`).
   - A **command that exits non-zero** is a normal `ExecResult`, **not** an exception
     (`ssh/ExecResult.java:9-14`, `succeeded() == (exitCode == 0)`). The box is fine;
     the *action* failed. The connectivity job maps a non-zero *probe* to `OFFLINE`
     (`ConnectivityCheckJob.java:132-133`), but a non-zero exit from a *user's action*
     says nothing about reachability and must **never** move machine status.
   Any signal path introduced here must key off the `SshExecutionException`
   (connect/auth) case only — never off a run's exit code.

2. **A failed *manual* test is the one no-debounce case.** `POST /machines/{id}/test`
   (`019` lines 38, 66-68, 115-121) is the operator explicitly asking "is this box
   reachable right now?". If that probe fails to connect, the honest answer is "no",
   and it should reflect **immediately** — no hysteresis, no confirmation round-trip.
   The user asked and got a definitive negative. This is the deliberate exception to
   the "one failure ≠ down" rule, because the user opted into a synchronous check.

## Options / Hypotheses

Weighed, not decided. All of them **complement** the periodic job rather than replace
it, and none touches the approval gate (see Coordination).

### Option A — `MachineUnreachableEvent` + hysteresis (K consecutive failures)

Add the mirror event, but gate the flip on **hysteresis**: a per-machine failure
counter (a "suspect" window) that a listener increments on each connect-failure and
that flips `UNREACHABLE` only after **K consecutive** failures, resetting to zero on
*any* success (a reached event, a good probe).

- **Pros:** conceptually symmetric with 019; the counter absorbs single transient
  failures, so no first-failure flap.
- **Cons:** introduces a *second* place (besides the job) that writes going-away
  status, so the flapping/authority logic now lives in two spots — the exact
  duplication 019 avoided by keeping the job as sole going-away authority. The counter
  is per-machine mutable state that must be concurrency-safe (many runs against one
  box in parallel) and survive restarts (or accept a reset-on-boot). Choosing K and
  the window is a genuine tuning problem, and the counter can itself drift out of sync
  with reality.

### Option B — failure triggers an immediate *confirmation probe* (recommended lean)

A connect-failure does **not** touch status. It publishes a lightweight signal (e.g.
`MachineSuspectEvent`) that asks the **authoritative** `ConnectivityCheckJob` probe
to run **now**, out of cron cadence, for that one machine. The job's existing
`probe → apply` logic (`ConnectivityCheckJob.java:117-137`) still decides
`ONLINE`/`OFFLINE`/`UNREACHABLE`, write-on-change as today. We are not adding a new
status writer — we are **tightening the cadence** of the one that already exists.

- **Pros:** reuses the *single* authoritative probe and its already-shipped
  write-on-change discipline, so there is **no new flapping surface** and no second
  status writer — the job stays the sole going-away authority (preserving 019's
  invariant, `ConnectivityCheckJob.java:46-54`). A transient blip resolves itself: the
  confirmation probe reconnects, sees `ONLINE`, writes nothing. Only a genuinely-down
  box confirms `UNREACHABLE`, seconds after the first failed run instead of minutes.
  Naturally de-duplicated (a burst of failing runs collapses to "probe this machine
  now").
- **Cons:** a down box is confirmed by a *second* connect attempt, so the flip is one
  extra round-trip slower than a first-failure flip (still seconds, not minutes) — and
  if the box is hard-down that second probe pays the full connect timeout
  (`ca.ssh.*-timeout-seconds`). Needs a dispatch mechanism (async, bounded,
  de-duplicated per machine) so a fleet-wide outage cannot fan out into an unbounded
  probe storm. Two consecutive genuine failures (the run + the confirmation) is itself
  a *de facto* K=2 hysteresis — worth stating plainly rather than pretending it's
  zero-debounce.

### Option C — manual-test-only (minimal)

Do nothing for passive failures: only a failed **manual** `POST /machines/{id}/test`
reflects immediately (distinction 2 above); passive run/discovery failures keep
waiting for the next cron tick.

- **Pros:** smallest possible change, arguably already implied by 019's manual-test
  endpoint; zero new events, zero flapping risk, zero tuning.
- **Cons:** does **not** actually solve the stated problem for the common case — a box
  that dies while nobody is staring at the test button still shows `ONLINE` for up to
  ~5 minutes. It's a floor, not a fix.

### Cross-cutting: a "suspect/degraded" status vs. reusing existing values

Option A (and optionally B's in-flight window) raises whether to add an intermediate
`MachineStatus` value — e.g. `SUSPECT`/`DEGRADED` — to represent "one failure seen,
not yet confirmed down", versus keeping the current four values
(`machine/model/MachineStatus.java:9-22`: `UNKNOWN`, `ONLINE`, `OFFLINE`,
`UNREACHABLE`). A new value is **not free**: it is a Flyway enum/constraint migration,
new UI pill states, and a decision about whether `SUSPECT` is auditable or purely
transient (a liveness signal is not a config edit — `019`/spec-003). Option B can
avoid it entirely by keeping the suspect state *in-flight* (a pending confirmation
probe) rather than *persisted*. Flagged as a first-class decision, not a detail.

## Open Questions

- **Threshold K** (Option A) or its implicit value in B (the run-failure + one
  confirmation probe ≈ K=2). What K trades staleness against flap resistance, and is it
  per-machine or global/configurable (mirroring `ca.connectivity.*`)?
- **Where is connect-vs-command classified?** The `SshExecutionException` vs non-zero
  `ExecResult` split already exists (`RunService.java:236-248`,
  `ConnectivityCheckJob.java:130-137`) — but the failure *signal* would need to be
  emitted from the caller that holds the `machineId` (the low-level `SshExecutor` sees
  only host/port, same constraint 019 hit). Exactly which call sites emit it (runs?
  discovery? both?) is open.
- **Does a failed *run* affect machine status at all, or only the run?** Today a failed
  run just lands `FAILED`/`-1` and publishes nothing (`RunService.java:244-248`).
  Should a *connect* failure during a run be allowed to nudge machine status (via B's
  confirmation probe), or should machine status remain strictly the job's/manual-test's
  business? This is the crux of how aggressive down-detection gets.
- **Suspect/degraded intermediate state** (+ migration) vs. none — see cross-cutting
  above.
- **How is the immediate confirmation probe dispatched** (Option B)? Almost certainly
  reuse of the bounded, single-thread `machineEventExecutor` pattern from 019
  (`config/MachineEventConfig`, per `019` lines 105-108), with **per-machine
  de-duplication** so a fleet outage can't storm; and a guard that a confirmation probe
  can't itself re-trigger another on failure (it just writes the authoritative result).
- **Interaction with the login-user-only detection limit.** The probe only tests the
  registered login user's SSH access (`SshTarget(host, port, loginUser)`,
  `ConnectivityCheckJob.java:86-89`). A pure *auth* failure for that user is
  indistinguishable from a down host at this layer, so "unreachable" here always means
  "unreachable *as this login user*" — a confirmation probe inherits the same limit and
  should not claim more than it proves.

## Leaning (not a decision)

Favor **Option B — a connect-failure signal triggers an immediate *authoritative
confirmation probe*** rather than any direct status flip, **plus immediate reflection
on a failed manual `POST /machines/{id}/test`** (distinction 2). This keeps the
periodic `ConnectivityCheckJob` as the **sole going-away authority** — no second
status writer, no new flapping surface — while tightening the down-detection loop from
minutes to seconds; the confirmation round-trip is itself the anti-flap guard (a
transient blip reconnects and writes nothing). It also refuses to let a non-zero
*command* exit or a single failure move status.

But this stays a **concern**: the threshold/implicit-K, whether a suspect/degraded
`MachineStatus` (with its migration) is worth it, whether a failed *run* may nudge
machine status, and the precise dispatch mechanism are the user's calls to make before
this graduates to a spec.

## Sequencing

**Independent of the machine-monitoring batch (spec-020)** — this is about connectivity
*status latency*, not the monitor UI/actions. It **builds directly on spec-019** (the
event + async listener + `machineEventExecutor` pool + `POST /machines/{id}/test`), so
**019 must be merged first (PR #30)**. Touches `machine` (job/event/model) and the SSH
failure classification already present in `run`. No dependency on 015/016/017/018.

## Coordination

- **Complements, does not replace, the periodic job.** Whatever option wins, the
  `ConnectivityCheckJob` remains the authority that owns hysteresis and the final
  going-away decision (`ConnectivityCheckJob.java:46-54`; `019` lines 41-43). The
  cron is the backstop that still corrects any missed or wrong signal within one
  interval.
- **Must not touch the approval gate.** Machine *status* is a liveness flag, **not an
  action** — this path only ever flips a status field. It must never run or approve an
  action, exactly as 019's listener is constrained (`MachineStatusUpdater` javadoc;
  `019` "MachineEventArchTest"; ARCH.md core invariant that the gate is enforced in one
  place and the thin adapters can't bypass it).
