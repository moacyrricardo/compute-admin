# 057 — App footprint probing

**Status:** done · Linear [BOL-886](https://linear.app/iskeru/issue/BOL-886) · build branch `moacyrricardo/bol-886-cpt-057-app-footprint-probing`. Stacked on 056 (BOL-885).

## Context

The monitor footprint math is entirely **client-side** today: `MonitorService`
(`monitor/service/MonitorService.java`) is a pure read-aggregate with **no sampler** — it
leaves every host-relative axis null and `app.js` fills ram/cpu/disk from a client-driven poll
(confirmed in `DockerConsumer`'s javadoc). The three host denominators (`denom.ramMb` from
`free -m`, `denom.cores` from `nproc`, `denom.diskBytes` from `df -h` on `/`) and the
single-denominator OTHER math (`computeOther`, `app.js:2385`, `OTHER = clamp(host_used% − Σ
attributed%, 0..100)`) already exist and are load-bearing. Docker consumers subtract cleanly
because every docker axis divides by those same three denominators (`applyDockerReading`,
`app.js:2790-2792`). **Native consumers do not:** their CPU uses a lifetime-average `%cpu`
(`CPU_PROBE_SCRIPT`, `AppMonitorDiscoverer.java:138`), their RAM sums RSS, and their **disk is
never attributed at all** — the spec-049 native-`du` disk axis was never built, so today a
native app's entire real disk footprint silently sits inside OTHER (`consumerAxis` renders it
"native — n/a", `app.js:2465`).

This spec graduates the **Probing** stage of concern 054 (locked decisions D3 and D5, plus the
Probing mechanism from the [feasibility report](054-assets/lightweight-app-model-report.html))
into buildable per-context probes: honest disk (`du` with the double-count rule), honest RAM
(PSS summed, never RSS-in-a-sum), and CPU as a **rate**. It sits fourth in the 055–060 epic and
**consumes spec-055's contract verbatim** — the `{app-script, script-folder, context}` record
(`AppPortItem` extended with `scriptFolder`/`contextKey`/`contextDisplay` + sibling list, all
un-audited side-data on the existing `app_port_list` JSON seam). It runs over the discovery
union that **spec-056** produces (ports + docker + systemd/cron non-listening sweep). It **feeds
spec-041's `computeOther`** (the integration point below) and hands its numbers to **spec-059**
for presentation. Standalone (non-docker) DB sizing is **out of scope → spec-058**.

## Decision

Per-context probing is committed as four axes, each a *different kind* of measurement, plus a
privilege posture with an on-demand upgrade path. Stated prescriptively (054 D3, D5):

1. **Disk = per-context state.** `du -sbx` on the resolved app-folder (`contextKey` from 055's
   record), **excluding every mount source under it**, plus each volume/bind counted **once**
   (deduped by resolved `realpath`) — the **double-counting rule** (054 report). The numerator
   is **bytes on the root/data-root filesystem** — the exact filesystem `parseDfTotal` /
   `parseDfUsedPct` anchor both `denom.diskBytes` and `hostUsed.disk` to. A disk numerator
   measured against any other mount is forbidden (it would break OTHER's single denominator).
2. **RAM = per-script instantaneous PSS, summed to the context.** Sum `Pss` from
   `/proc/<pid>/smaps_rollup` across the context's PIDs. **Never sum RSS** — summing RSS across N
   workers sharing libraries overstates by up to (N−1)×shared. RSS (`VmRSS`) is the degraded
   fallback only, labelled **≤ upper bound**, never presented as the same metric.
3. **CPU = a rate, not a level.** Δ(`utime`+`stime`) (`/proc/<pid>/stat` fields 14+15) sampled
   **twice inside one SSH exec**, `Δt` apart, divided by the *measured* `Δt` and `CLK_TCK`.
   `starttime` (field 22) guards against PID churn between the two samples. The lifetime-average
   `ps %cpu` path is **replaced** — it is meaningless for a worker that burned CPU once then idled.
4. **DB (docker-only) = two numbers — the `{logicalBytes, physicalBytes}` pair.** `logicalBytes`
   (`docker exec … pg_database_size()` / `information_schema` sum, MySQL served fresh with
   `SET SESSION information_schema_stats_expiry=0`) **and** `physicalBytes` (`du` on the data
   volume). Both reported as raw bytes, held **client-side** (never persisted server-side, matching
   the client-driven fold below); the gap is signal (WAL/bloat). This `{logicalBytes,
   physicalBytes}` pair is the DB metric-pair shape spec-058 fills for the standalone (host-side)
   engines. **Standalone-DB sizing is deferred to spec-058** (same SQL, host-side transport).
5. **Three cadence tiers, one SSH exec per poll each:** fast procfs/cgroupfs (PSS, CPU-rate incl.
   its sleep, `df`) at **30–60 s**; medium docker daemon (`docker stats --no-stream`, `ps -s`,
   cheap logical DB size) at **1–5 min**; slow tree walks (`du`, `docker system df -v`, physical
   DB volume `du`) at **hourly → daily + on deploy**. The fast tier never blocks on the slow tier;
   a stale-but-timestamped disk number beats a fresh one that hammered the host.
6. **Privilege posture — degrade and label (054 D3).** Every probe runs **no-sudo by default**.
   When a read is denied: emit `permission-denied` / `confidence=low` (never a fake `0`), fall
   back to RSS-as-upper-bound for RAM, and to the **cgroup** aggregate instead of per-PID sums.
   "No PID" / a blank users column is **never** read as "no process". **On-demand re-probe with
   sudo (054 D3 note):** the operator may add a per-context **sudo MONITOR action** that upgrades
   a degraded reading to full fidelity (PSS, per-PID, other-user paths). It is approved and gated
   like any sudo action; it never auto-runs.

## Implementation

**No migration, no schema change, no new entity.** Every probe is an ordinary `RecipeType.MONITOR`
action (display metadata only — ARCH.md: MONITOR does not change the gate; each still requires UI
approval to run). Per-context inputs ride as **runtime-bound param values drawn from the
un-audited `app_port_list` side-data** that spec-055 already extended (`contextKey`,
`scriptFolder`) — exactly the fan-out seam `Proposals.appPortList(...)` uses for `port`/`appName`
today. The context path is therefore **never a LITERAL `argToken`**, never inside
`ActionSnapshot.hash`, and never crosses MCP (S9 — see below). The probe scripts themselves are
constant, read-only, `sh -c` strings with only validated params bound (S4).

### New probe scripts (proposal side)

Add alongside the existing `PROCESS_PROBE_SCRIPT` / `CPU_PROBE_SCRIPT` in
`discovery/service/AppMonitorDiscoverer.java` (built via the `Proposals` helpers; family stays
`APP`, so `DiscoveryService`'s injected `List<RecipeDiscoverer>` picks them up with no wiring
change):

- **`PSS_RAM_PROBE_SCRIPT`** — for each PID of the context, `awk '/^Pss:/{s+=$2} END{print s}'
  /proc/<pid>/smaps_rollup`; sum to the context in kB → MB. On `smaps_rollup` denied, fall back to
  `VmRSS` from `/proc/<pid>/status` and stamp `ram_confidence=low, reason=procfs-denied`. Emits a
  resident-bytes numerator (never a cgroup-limit-relative %).
- **`CPU_RATE_PROBE_SCRIPT`** — sample fields 14+15+22 of `/proc/<pid>/stat` for the whole process
  tree, `sleep 4`, sample again, `date +%s.%N` on both; emit `Δticks`, measured `Δt`, and per-PID
  `starttime` so the client divides by `CLK_TCK` and the real `Δt`. Numerator is **cross-core**
  `%cpu` (Σ per-process core-fractions × 100; may exceed 100), matching `denom.cores`. Replaces the
  lifetime-average `CPU_PROBE_SCRIPT` as the native CPU source.
- **`DISK_PROBE_SCRIPT`** — `timeout 120 nice -n19 ionice -c3 du -sbx --exclude=<mount-source>…
  <app-folder>` (the app-folder and its under-mount exclusions bound from side-data), then add each
  distinct mount once (`findmnt -rn -o TARGET` cross-checks the exclusion list). On timeout, fall
  back to `du --max-depth=1` per child, summing what completes (a labelled lower bound). Numerator
  is **bytes on the root/data-root FS** — the same FS `parseDfTotal` measures.
- **`DOCKER_DB_SIZE_SCRIPT`** (docker branch, hung off `DockerComposeDiscoverer` /
  `DatastoreImages` classification) — `docker exec <cid>` logical size query, credentials read from
  the container env (`docker exec <cid> printenv POSTGRES_USER` / `MYSQL_ROOT_PASSWORD`); physical
  size is the data-volume `du` on the slow tier. Reports both.

### Client-side folding (spec-041 integration — the load-bearing part)

Extend the existing poll/fold functions in `app.js`; **`computeOther` (`app.js:2385`) is not
touched**. Its `attr(axis)` blindly sums `c[axis]` over the rendered `named` consumers, so the
moment a native axis becomes non-null it is **automatically** subtracted from OTHER — correct
**only because** each numerator uses the matching host denominator:

- Add parsers mirroring `parseRssMb` (`app.js:2273`) / `parseAppCpu` (`app.js:2291`): `parsePss`
  (kB → MB), `parseStatTicks` (Δticks + Δt → cross-core %), `parseDuBytes`.
- In `applyConsumerReading` (`app.js:2670`) set, for the native consumer:
  `c.ram = pssMb / denom.ramMb * 100`, `c.cpu = clampPct(round(ticksPct / denom.cores))`,
  `c.disk = duBytes / denom.diskBytes * 100`. **Plumbing note:** the current signature
  `applyConsumerReading(c, outputs, hostTotal, cores)` and its caller
  `pollConsumers(m.machineId, selectedNamed(m), denom.ramMb, denom.cores)` (`app.js:2071`) thread
  only `ramMb`/`cores` — the native path **never receives `denom.diskBytes`** (unlike
  `applyDockerReading`, which takes the whole `denom`). Thread `diskBytes` (or pass the full
  `denom`) through `pollConsumers` → `applyConsumerReading` so the disk axis divides by the same
  root-FS denominator `applyDockerReading` (`app.js:2790-2792`) uses. This finally fills the native
  disk axis spec-049 left null; the
  spec-041 note (`specs/041-…:97-101`) flagged exactly this — native disk both draws its own
  segment and shrinks OTHER, which is right **iff** it used the root-FS denominator.
- **Honest absence:** emit nothing (axis stays null → `—`) rather than `0` when a context has no
  attributable value or the read was denied, matching the `!= null && denom` guards. A bogus `0`
  would silently inflate OTHER.
- Drive the three cadence tiers from the poll loop (`pollConsumers`, `app.js:2634`): procfs probes
  every 30–60 s, docker every 1–5 min, `du`/`system df -v`/physical-DB on the slow tier, each result
  cached with a timestamp. Presentation of these numbers (segments, legend, confidence labels,
  the re-probe control) is **spec-059's** surface, not this spec's.

### Re-probe-with-sudo action

A per-context `sudo=true` MONITOR action whose script is the sudo variant of the PSS/disk probes
(per-PID reads of other users' `/proc`, other-user dirs). Approved and run through the ordinary
gate (`RunService.run`, `APPROVED` + unmutated-hash check); S5 (passwordless sudo) applies. It
replaces a degraded reading in place and clears its `confidence=low` label on success.

### Gate, S9, and MCP surface

**No MCP-surface delta.** No new tool; the probe actions are discovered via the existing
`DiscoverRecipesTool` proposal path and approved REST-only. `GateArchTest` /
`blueprint/BlueprintGateTest` stay green untouched (no `ApprovalService`/`*Repository` reference,
no tool name containing "approve"). **S9 stays green** because the context path never becomes a
LITERAL `argToken` — it is bound at run time from `app_port_list` side-data — so the arch test
spec-055 introduces (no MCP tool emits a raw absolute path, closing the `ListActionsTool`
argToken leak) has nothing new to catch here. The MCP-facing identity remains the basename-derived,
human-accepted label (053 dec. 12 / spec-055).

## Known Gaps

- **Standalone (non-docker) DB sizing** — deferred to **spec-058** (054 D5 note). 057 sizes
  **docker-only** DBs; a bare host postgres/mysql gets no logical size here.
- **Presentation** — the visual surface for these numbers (per-context footprint cards, segmented
  meters, confidence badges, the re-probe trigger) is **spec-059**. 057 stops at the probe actions
  and the client fold that lands values on `c.ram/c.cpu/c.disk`.
- **Discovery/mapping** — 057 assumes 055's record and 056's discovery union already resolve the
  context and its PIDs/mounts. 057 adds no new discovery sweep and does not re-derive the
  wrapper-dir/symlink key.
- **No server-side sampler** — footprint math stays client-driven (honouring spec-040's thin-BE
  posture); 057 does not move sampling into `MonitorService`. A future spec may, if client polling
  proves insufficient for the slow tier.
- **`du` on a giant tree** degrades the *probe* (timeout → lower bound), never the host; a fully
  accurate disk figure under adversarial trees is explicitly not guaranteed.

## Implementation Notes

Built as specified; the four axes, the client fold onto `c.ram/c.cpu/c.disk`, and the
`computeOther`-untouched integration all landed as prescribed. Where the build refined the spec:

- **`app-folder` binding seam.** Rather than a bespoke side-channel, the per-context path rides
  the existing `ParamBinder` component mechanism as a new `APP_FOLDER_COMPONENT`, enriched
  server-side in `RunService` from the recipe's un-audited `app_port_list` side-data (keyed by
  `(appName, port)`). It is validated against a new anchored, shell-safe absolute-path charset
  (`APP_FOLDER_PATTERN`) as defense-in-depth (S4) even though it is never caller-supplied — so a
  tampered side-data value still cannot widen the shell surface. Fan-out items with no resolved
  context are dropped from the disk probe rather than probed with a null path (honest absence).
- **Degrade-and-label parity (Decision 6) for disk.** The fold initially carried only the RAM
  low-confidence flag (`_ramLow`); the disk `du`-timeout lower bound (`disk_confidence=low
  reason=du-timeout`) was filling `c.disk` silently. Added a matching `c._diskLow` flag so
  spec-059 badges the disk lower bound exactly as it badges the RAM RSS fallback. This is a
  pure data-carry in the fold — presentation of the badge remains spec-059's surface.
- **CPU-rate column safety.** The `/proc/<pid>/stat` parse reads fields 14+15+22 *after the last
  `") "`* (an `awk index($0,") ")` split), so a `comm` containing spaces/parens never shifts the
  columns — a robustness detail the spec implied ("`starttime` guards PID churn") but did not
  spell out.
- **Tests.** Probe-script and fold behaviour is pinned by `AppMonitorDiscovererTest` /
  `DockerComposeDiscovererTest` (Java) plus the headless `app-footprint-probing.render-check.js`
  (the client fold, PSS-not-RSS, CPU-rate + churn guard, `du` double-count/degrade, the OTHER
  subtraction). No migration, no schema change, no new entity, no MCP-surface delta, no version
  bump — as scoped.
