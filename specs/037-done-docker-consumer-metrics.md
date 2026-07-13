# 037 — Docker consumer metric polling

> **Status: done.** Branch `moacyrricardo/spec-037-docker-consumer-metrics`, stacked on
> `moacyrricardo/spec-034-fleet-monitor-ui-redesign` (merge after spec-034). No Flyway
> migration. Linear blocked (no ticket).

> Builds directly on the docker parsers of
> [033](./033-done-docker-container-discovery.md) (`parseDockerStats` /
> `parseDockerPs` in `app.js`) and the consumer poll of
> [034](./034-done-fleet-monitor-ui-redesign.md) (`pollConsumers` /
> `applyConsumerReading` / `buildConsumers`), on the consumer contract of
> [032](./032-done-monitoring-axes-foundations.md), and honours the one-shot-sample
> caveats catalogued in [031](./031-todo-deferred-followups-triage.md).

## Context

spec-033 discovers docker compose-project / datastore consumers and spec-034 renders
them on the three host-relative axes (RAM · CPU · disk). But those axes read `—` for
every docker consumer, because the poll path that would fill them was never built:

- 034's consumer poll (`pollConsumers` / `applyConsumerReading`) only drives the
  **`(app-name, port)` `APP_PORT_LIST` fan-out** — the native app-monitor probes
  (spec-025). It groups consumers by their shared fan-out check, runs it once per app
  set, and fills only the **RAM** axis (summed `VmRSS` ÷ host total). A docker consumer
  has **no** `port` and **no** `APP_PORT_LIST` check, so `pollConsumers` skips it
  entirely (`pollable = consumers.filter(c => c.checks.length && c.port != null)`).
- 033's docker checks are **param-free** host-panel `MONITOR` actions (`docker stats
  --no-stream`, `docker ps -s`, `docker system df -v`) and its parsers
  (`parseDockerStats` / `parseDockerPs` / `dockerSummary`) exist in `app.js` — but they
  have **no caller in 034's poll**. 033 only made an approved docker monitor *legible*
  (degrade-to-raw in an action's output view); it never fed the consumer axes.

So the wiring between "033 can parse a container's metrics" and "034 renders a
consumer's axes" is the single missing piece. This spec builds that poll path so docker
consumers show live host-relative metrics.

## Decision

1. **Add a param-free docker-check poll to the per-machine consumer refresh.** Beside
   `pollConsumers`, run each **APPROVED, un-drifted, param-free** docker `MONITOR` check
   (`docker stats --no-stream`, `docker ps -s`, `docker system df -v`) **ONCE per
   machine** — they enumerate every container in one read, so there is no per-consumer
   fan-out. Parse each with the existing 033 parsers (`parseDockerStats` /
   `parseDockerPs`, plus a new `parseDockerVolumes` for the `system df -v` table).

2. **Aggregate per-container metrics up to the consumer.** A compose-project consumer
   **sums its member containers**; a standalone / shared datastore consumer maps **1:1**.
   Container → consumer keys on the container **name**, which is exactly what 033 stored
   in each `ConsumerServiceView.name` (the `docker ps` `Names` field, the same key
   `docker stats`/`docker ps -s` report) — so the consumer's own `services[]` list is
   the join. Per-service axes are filled too, so the drawer's service rows show numbers.

3. **Normalize every axis to % of host** (the 032 axis contract, `0..100` = share of the
   machine; `clamp 0..100`; absent → `null` → `—`):
   - **RAM%** = `sum(container mem-usage bytes) ÷ host total RAM`. Reuse the host RAM
     total already parsed for the native mem axis (the `free -m` host monitor,
     `hostMemTotal[machineId]`). Parse **ABSOLUTE** usage from `docker stats` **MemUsage**
     (`"1.5GiB / 3.8GiB"` → first value) — **not** `MemPerc`, which is
     container-limit-relative, not host-relative.
   - **CPU%** = `sum(container docker stats CPUPerc) ÷ host core count`. `docker stats`
     CPUPerc already sums across cores (a 2-core-busy container reads ~200%), so dividing
     by the logical core count re-bases it to % of the whole host. Source the core count
     from a **bounded read-only `nproc`** added to the host-vitals recipe (spec-023's
     `monitor machine`), polled like the RAM denominator; if unavailable, **degrade CPU
     to `—`**. Clamp 0..100.
   - **Disk%** = `sum(container writable-layer bytes + project named-volume bytes) ÷ the
     docker data-root filesystem total`. Writable layer per container from `docker ps -s`
     (`Size`, the leading value before `(virtual …)`); named volumes from `docker system
     df -v`, attributed to a compose project by the `<project>_…` volume-name convention
     (best-effort — see Known Gaps). The data-root filesystem total is read best-effort
     from the host `df -h` disk monitor (the `/` filesystem as the data-root proxy);
     absent → disk `—`.

4. **Fill each consumer's `ram`/`cpu`/`disk`** (and each service's) so 034's segmented
   bars, legend, cards, and drawer render them. Every degradation is honest: an
   unparseable reading or an absent denominator yields `—` (never a silent `0`) — the
   spec-023/025 degrade-to-raw / spec-032 §1 "absent is null" rule.

## Implementation

The poll addition is **client-side** in `app.js`, mirroring 034/033's client parsing —
plus one **discovery** (write-side) addition for the CPU denominator. There is **no
server read-side change**: docker consumers already carry their container identity via
the 032 contract (`services[]`), and the docker checks already surface as param-free
host actions. **No Flyway migration.**

- **`app.js`** — a param-free docker poll (`pollDockerConsumers`) beside `pollConsumers`,
  reusing `parseDockerStats` / `parseDockerPs` and a new `parseDockerVolumes`; an
  `applyDockerReading(consumer, stats, ps, volumes, denom)` that sums the consumer's
  `services[]` and fills the three axes + the per-service axes; a `dockerBytes` unit
  parser (`kB/MB/GB/TB` SI and `KiB/MiB/GiB/TiB` binary → bytes). Two new host-denominator
  polls: `pollHostCores` (`nproc` → integer) and `pollHostDiskTotal` (`df -h` → the `/`
  filesystem total in bytes), cached per machine alongside `hostMemTotal`. `refresh()`
  gathers the three denominators, then runs `pollConsumers` **and** `pollDockerConsumers`
  before `paint()`.
- **`MonitorMachineDiscoverer` (spec-023)** — add a fourth read-only, param-free action
  `cores` (`nproc`) to the universal `monitor machine` recipe, so the host core count is
  an approvable host vital. Its name avoids the `cpu`/`load` substrings so `metricKind`
  does not confuse it with the `top -bn1` host-CPU action; the client finds it by name.
  Re-discovery reconciles the recipe in place (spec-021).
- Gate untouched, `mcp/` untouched, `*ArchTest`s untouched; `textContent`-only, no
  `innerHTML` (the `h()` invariant).

## Known Gaps

- **One-shot `docker stats` CPU sample** — a single `--no-stream` read, not a two-sample
  delta; the point-in-time caveat of concern 031 / spec-023 carries over.
- **Shared-volume size attribution** — `docker system df -v` reports a volume's size and
  a link *count*, not *which* containers use it. Named volumes are attributed to a
  compose project only by the `<project>_…` name convention; a shared volume used by
  several projects, or a bind mount, is not attributed (and a shared engine's volume is
  deliberately not split — spec-032 §4 / 034's shared band).
- **`docker stats` MemUsage unit parsing** — depends on docker reporting binary units
  (`GiB`); an exotic locale/format that `dockerBytes` cannot parse degrades that
  container's RAM to absent (the consumer still shows the containers that did parse).
- **`nproc` availability** — if the `cores` host vital is unapproved or the tool is
  missing, CPU degrades to `—`; RAM and disk are unaffected.
- **Disk denominator** is the `/` filesystem as a proxy for the docker data-root
  (`/var/lib/docker`); when they differ, or `df -h` is unapproved, disk shows `—`
  (033's stated data-root caveat).

## Divergence from the spec (as built)

Implemented faithfully to the four decisions; the notes below are the only deltas:

- **UP / probe-chip synthesis for docker consumers.** Beyond filling the axes, the
  poll also sets each docker consumer's `_up` (a running container ⇒ UP), `_anyApproved`,
  and a small `_checkStates` list (`docker stats` / `docker disk`, only the reads that
  produced data) so 034's consumer card renders a live pill and responded-probe chips
  rather than "no data" / "approve to see". The spec text implied the axes only; this
  keeps the card honest and consistent with the native path's `applyConsumerReading`.
- **Committed the headless render check** at `src/test/js/docker-consumer-metrics.render-check.js`
  (there is no in-repo JS test runner, so it is not wired into `mvn`; run with
  `node src/test/js/docker-consumer-metrics.render-check.js`). It loads the real
  `app.js` in a minimal DOM stub and asserts a docker consumer with a parsed `docker
  stats` reading renders numeric axes (ram 51% / cpu 50% / disk 3%) and UP — plus the
  `dockerBytes` / `parseDfTotal` / `parseDockerVolumes` unit dialects.
- **Denominator sourcing, as decided.** The CPU denominator is the new `cores` (`nproc`)
  host vital (spec-023's `monitor machine` recipe gained a fourth action); the disk
  denominator is the `/` row of the existing `df -h` disk vital. Both degrade to `—`
  when unapproved/absent. Named volumes are attributed to a compose project by the
  `<project>_…` name convention (the stated best-effort; not split across projects).
- **Verification.** `node --check src/main/resources/static/app.js` clean; the full
  `mvn test` suite is green — **249 tests, 0 failures, 0 errors, 0 skipped** (incl. the
  ArchTests / `GateArchTest`); the headless render check passes. Gate + `mcp/` +
  `*ArchTest`s untouched.
