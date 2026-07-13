# 039 — Native consumer CPU axis

> **Status: done.** Branch `moacyrricardo/spec-039-native-cpu-axis`, stacked on
> `moacyrricardo/spec-038-compose-project-grouping` (merge after spec-038). No Flyway
> migration. Linear blocked (no ticket).

> Builds on the native process-tree CPU probe of
> [032](./032-done-monitoring-axes-foundations.md) (the app-level `cpu` check +
> `checkKind`), the consumer CPU **axis** of
> [034](./034-done-fleet-monitor-ui-redesign.md), and the docker CPU wiring of
> [037](./037-done-docker-consumer-metrics.md) (the `cores`/`nproc` host vital and
> `denom.cores` denominator). It honours the one-shot-sample caveats catalogued in
> [031](./031-todo-deferred-followups-triage.md).

## Context

The three pieces of a **native** consumer's CPU axis exist, but the last wire between
them was never soldered:

- **spec-032** added a native process-tree CPU probe to every app-monitor family — a
  port-bound `cpu` check that resolves the listener PID(s) and reads each PID's (and its
  direct children's) `%CPU` via `ps -o pid=,ppid=,pcpu=,comm=`, emitting a `## pid N`
  header per tree — and taught `checkKind()` to recognise the `cpu` kind.
- **spec-034** introduced the consumer CPU **axis** (the second of the three
  host-relative RAM · CPU · disk meters).
- **spec-037** wired the **docker** CPU into that axis: `applyDockerReading` sums each
  container's `docker stats` CPUPerc and divides by the host **core count** (the
  `cores`/`nproc` host vital 037 added, polled by `pollHostCores` into `denom.cores`).

But **native / `http` consumers never got the same wiring.** In `applyConsumerReading`
(the `pollConsumers` path, `app.js`), the `kind === "cpu"` branch turns the native cpu
check into an **up/down chip only** — its `%CPU` stdout is **never parsed into
`c.cpu`**. Contrast the RAM axis, which the same function fills from the `process`
check (`c.ram = clientMemPct(rssMb, hostTotal)`). So an approved, data-returning native
cpu check still leaves the consumer's CPU axis reading `—` ("no data") forever — the
symmetric gap 037 fixed for docker, still open for native.

## Decision

Fill a native consumer's CPU axis the same way 037 fills docker's — parse the probe,
normalize to % of host, clamp, and degrade honestly:

1. **Parse the native cpu probe's `%CPU`.** Sum the numeric `pcpu` values across the
   process-tree stdout (the 3rd whitespace field of each `ps` data row), skipping the
   `## pid N` headers and the `no listener…` sentinel. No parseable value → `null`.

2. **Normalize to % of host by dividing by the host core count.** Reuse 037's
   `cores`/`nproc` denominator — the **same** value docker's CPU axis uses
   (`denom.cores`, from `pollHostCores`). `ps` `%cpu` is per-core (a two-core-busy tree
   reads ~200%), so dividing by the logical core count re-bases it to % of the whole
   host. Set `c.cpu = clampPct(Math.round(cpuRaw / cores))`, mirroring
   `applyDockerReading`'s `c.cpu = clampPct(Math.round(sumCpu / denom.cores))`.

3. **Honest `—`.** When the cpu check is unapproved / absent, returns nothing
   parseable, or `cores` is unknown, leave `c.cpu` `null` so the axis renders `—`
   (never a silent `0`) — consistent with the 032 §1 "absent is null" rule and the
   mem axis's "approve … to see" hint.

4. **Keep the up/down chip unchanged.** The `kind === "cpu"` branch still rolls the
   check up to an up/down probe state; parsing the axis is purely additive.

## Implementation

Client-side in `app.js` only, mirroring 037's docker path. **No server change** (the
native cpu probe and the `cores` host vital already exist), **no Flyway migration**.

- **`parseAppCpu(text)`** — a new parser beside `parseRssMb`: split the stdout into
  lines, skip blanks / `## …` headers / non-numeric 3rd fields, sum the numeric `pcpu`
  values, return `null` if none parsed.
- **`applyConsumerReading(c, outputs, hostTotal, cores)`** — gains the `cores`
  denominator. In the `kind === "cpu"` branch, parse + accumulate the pcpu into a
  running `cpuRaw` while still computing the up/down `state`. After the checks loop, set
  `c.cpu = (cpuRaw != null && cores) ? clampPct(Math.round(cpuRaw / cores)) : c.cpu` —
  so an unapproved/absent/undetermined reading, or an unknown core count, leaves the
  axis `null` (→ `—`).
- **`pollConsumers(machineId, consumers, hostTotal, cores)`** — threads the `cores`
  denominator through to `applyConsumerReading` (both the has-outputs and no-groups
  calls). `refresh()` already computes `denom.cores` (037) and passes `denom.ramMb`; it
  now also passes `denom.cores` — the exact value docker uses.
- Gate untouched, `mcp/` untouched, `*ArchTest`s untouched; `textContent`-only, no
  `innerHTML` (the `h()` invariant).
- **Headless render check** — extend `src/test/js/docker-consumer-metrics.render-check.js`
  to expose `applyConsumerReading` / `parseAppCpu` and assert a NATIVE consumer with a
  parsed cpu reading shows a **numeric** CPU axis (not `—`), and that an
  unapproved/absent cpu check (or an unknown core count) stays `—`.

## Known Gaps

- **One-shot `ps -o pcpu` sample** — a single point-in-time read of `ps`' *lifetime*
  `%cpu`, not a two-sample delta; the same caveat as `docker stats --no-stream` and
  concern 031.
- **Multi-process-tree summation** — the pcpu of a PID plus its direct children is
  summed naïvely; the shared-memory / backend double-count and the single-`ps`-level
  bound are the caveats inherited from spec-032's probe, documented, not solved, in v1.
- **`cores` availability** — if the `cores`/`nproc` host vital is unapproved or missing,
  the native CPU axis degrades to `—` (exactly as docker's does); RAM is unaffected.

## Divergence from the spec (as built)

Built faithfully to the four decisions; the notes below are the only deltas:

- **Doc-comment refresh.** Beyond the wiring, `applyConsumerReading`'s Javadoc and the
  `pollConsumers` section comment were updated to state that CPU is now filled
  client-side (they previously said "only the RAM axis is filled" / "fill each
  consumer's RAM axis") — a comment-accuracy touch, no behaviour change.
- **Render check extended in place.** The spec-037 headless check
  (`src/test/js/docker-consumer-metrics.render-check.js`) gained a spec-039 block rather
  than a sibling file: it exposes `applyConsumerReading` / `parseAppCpu`, asserts
  `parseAppCpu` sums pcpu (80+20=100) and returns `null` for the no-listener/empty
  sentinels, and drives a native consumer to a numeric CPU axis (100 pcpu ÷ 4 cores =
  25%) on the card + legend, staying `—` for an absent reading or unknown `cores`.
  (Not wired into `mvn`; run with `node src/test/js/docker-consumer-metrics.render-check.js`.)
- **Verification.** `node --check src/main/resources/static/app.js` clean; the headless
  render check passes (docker + spec-038 + spec-039 blocks); the full `mvn test` suite is
  green — **249 tests, 0 failures, 0 errors, 0 skipped** (incl. `GateArchTest` /
  `MachineEventArchTest`). Gate + `mcp/` + `*ArchTest`s untouched.
