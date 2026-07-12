# 020 — Machine monitoring (concern)

> **Concern** (exploratory — options open, not a decided spec). Linear is BLOCKED;
> commits use `spec-020`. Resolves to a spec once the questions below are answered.

## Problem

We want to **monitor** a machine's health — starting with **memory**, and almost
certainly **CPU/load** and **disk** too. The question is *how* monitoring should fit
compute-admin's model: it is an SSH-recipe engine with a **UI-only approval gate** on
every action, no time-series storage, and no scheduler beyond the connectivity cron and
the run-output eviction job. "Monitor overall memory" can mean anything from "run
`free -m` and show it" to "sample every machine every minute, chart it, and alert on a
threshold" — those are wildly different sizes.

## Hypotheses / Options

**(A) On-demand monitor recipe (small; fits the model).**
A built-in `MONITOR` recipe type whose actions are **read-only** metric reads —
memory (`free -m`), disk (`df -h`), load/procs (`uptime`, `top -bn1`). You run them like
any recipe and view the captured output. Reuses the entire existing run/stream/audit
machinery; nearly free.
- *Pro:* tiny, consistent, no new subsystem.
- *Con / open question:* every action today requires **UI approval** before it can run.
  Approving a harmless `free -m` read is friction — so do read-only monitor actions get a
  **carve-out from the gate** (auto-approved / a distinct "read-only" action class)? That
  touches the core invariant. The invariant is really about **mutating** actions; a
  principled "read-only actions may run without approval" rule could be sound — but it is a
  real security decision that must be made explicitly, not slipped in.

**(B) Continuous polling + dashboard/alerts (large; a real monitoring subsystem).**
The app periodically samples memory/CPU/disk across machines, stores a **time-series**,
renders **charts**, and fires **threshold alerts**.
- *Pro:* actual "monitoring" (trends, alerting, at-a-glance fleet health).
- *Con:* new storage + retention, a sampling scheduler (bounded like the connectivity
  job), an alerting channel, and substantial UI. Large; multiple specs' worth.

**(C) Hybrid / staged.** Ship (A) now (on-demand read-only reads), and treat (B)
(sampling, charts, alerts) as a follow-up once the read actions and the gate carve-out
exist.

## Open Questions

1. **Metrics scope for v1:** memory only, or memory + CPU/load + disk (+ top processes)?
2. **The approval gate for read-only actions** (the crux): are metric reads auto-runnable,
   or do they go through the same UI approval as mutating actions? If auto-runnable, how do
   we *structurally* distinguish read-only from mutating so the carve-out can't be abused to
   run a mutating command unapproved (mirroring how `GateArchTest` structurally protects the
   invariant today)?
3. **Delivery:** on-demand (run + view, option A) vs continuous (sample + chart + alert,
   option B) vs hybrid (C)?
4. **If continuous:** sampling cadence, time-series storage (H2 rows? a bounded ring?),
   retention, and where the sampling scheduler lives (a bounded job like connectivity).
5. **Alerting (if any):** thresholds per-machine vs global; channel (UI banner only? email —
   which we don't have; the S-register notes no SMTP).
6. **Discovery:** is a monitor recipe **auto-proposed** by discovery (like nginx/docker), or
   a fixed built-in every machine gets?
7. **View:** per-machine panel vs a fleet-wide health overview.

## Leaning (to be confirmed)

Start with **(A)**: a built-in read-only `MONITOR` recipe (memory + disk + load), and
**resolve Q2 head-on** — most likely a structural "read-only action" class that may run
without UI approval, guarded the same way the gate is (an ArchTest-style invariant), with
mutating actions still strictly gated. Defer continuous sampling / charts / alerting (B) to
a follow-up spec if the on-demand reads prove insufficient. This keeps the first increment
small and inside the existing model while forcing the one genuine security decision into the
open. **Not prescriptive yet** — this graduates to a spec once Q1–Q3 (at least) are decided.

## Sequencing

Independent of 018/019, though it benefits from them (tag/filter monitored machines;
"reached" events could gate sampling). Do **not** start building until this concern is
resolved into a spec — the approval-gate carve-out (Q2) is a decision that must not be left
to the implementer.
