# 068 — Host facts & connectivity metadata

**Status:** todo · Linear [BOL-897](https://linear.app/iskeru/issue/BOL-897) · build branch `moacyrricardo/bol-897-cpt-068-host-facts-connectivity` · **Part of concern 064 (mockup delivery).**

## Context

The mockup's machine dashboard (Screen C, `specs/054-assets/mockup-compute-admin.html`) leads with
a **host facts strip** — OS `Ubuntu 24.04`, Kernel `6.8.0-31`, Uptime `18d 4h`, Cores `8`, RAM
`16 GB`, Arch `arm64` (`mockup-compute-admin.html:792-799`, strip styling `:317-322`) — plus
connectivity metadata: "last probed 2m ago" in the page head (`:783-784`) and a `Handshake · 2m ago
· 180 ms` row in the SSH/connectivity panel (`:915`). The register screen's test-connection result
shows the same facts + "handshake ok in 180 ms" (`:1037-1045`).

The live app has **none of this data**:

- `MachineView` is `(id, name, host, port, loginUser, status, tags)` — no facts, no timestamps
  (`machine/api/MachineDtos.java:41-48`). The machine detail head renders only
  `loginUser@host:port` + status chip + tags (`static/app.js:658-664`).
- A facts probe **exists but discards everything the strip needs**: spec-018's
  `MachineFactsProbe` reads `/etc/os-release` and DMI vendor files, read-only `cat`, no sudo
  (`machine/service/MachineFactsProbe.java:50-101`) — but reduces them to two tag strings,
  `MachineFacts(os, cloud)` (`machine/service/MachineFacts.java:12`), and runs **once per machine
  ever**, guarded by `factsProbedAt` (`machine/event/MachineFactsTagger.java:62-65`,
  `Machine.java:109`, `V8__machine_facts_probed_at.sql`). OS *version*, kernel, cores, RAM, arch,
  uptime are never captured.
- Connectivity has **no durable timestamps or latency**. `MachineReachedEvent` carries an `at`
  instant (`machine/event/MachineReachedEvent.java:24`), but `MachineStatusUpdater` persists it
  only as `updatedAt` **on a status change** (`MachineStatusUpdater.java:61-64`); the cron job
  likewise writes nothing for an unchanged probe (`ConnectivityCheckJob.java:123-126`). Neither
  path measures duration — `ExecResult` is `(exitCode, stdout, stderr)` (`ssh/ExecResult.java:9`),
  and both probes just run `true` (`ConnectionTestService.java:69-77`,
  `ConnectivityCheckJob.java:130-137`). "Last probed 2m ago · 180 ms" is unanswerable today.
- The mockup tests the connection **before** registering (`mockup-compute-admin.html:553-563`);
  the live flow can only probe **after** — register, then `POST /machines/{id}/test`
  (`app.js:569`, endpoint `machine/api/MachineRS.java:94-98`).

Server + presentation; net-net-new backend. Sibling of 065–067 under concern 064; independent of
the 063 seam (different DTOs, different route).

## Decision

1. **A host-facts probe on the reach path.** A new read-only probe captures
   `(osName, osVersion, kernel, arch, cores, memTotalBytes, uptimeSeconds)` over the existing
   `SshExecutor` port using a **fixed argv** — `cat /etc/os-release`, `uname -r`, `uname -m`,
   `nproc`, `cat /proc/meminfo`, `cat /proc/uptime` — no sudo, no caller-controlled binding
   (nothing for ARCH S4's param-injection posture to even bind), same "never mutate the box" rule
   and best-effort null-per-facet contract as spec-018's probe (`MachineFactsProbe.java:20-24`).
2. **Facts are persisted on the machine row and refreshed, not once-ever.** New nullable columns +
   a `hostFactsAt` stamp, refreshed on `MachineReachedEvent` behind a staleness TTL and by the
   manual test endpoint. Spec-018's once-only tagger and its `factsProbedAt` guard are untouched
   (tags stay add-only; facts are a separate, refreshable read-out).
3. **Handshake latency + last-probed on the status/test path.** Both `true`-probe call sites time
   the exec and persist `lastProbedAt` / `lastLatencyMs` un-audited — a liveness read-out is not a
   config edit (the spec-003 rule both writers already enforce, `ConnectivityCheckJob.java:120-122`).
4. **Exposed on `MachineView` only.** `MachineView` gains `facts` + `connectivity` sub-records;
   `McpMachineView` (`MachineDtos.java:59`) is unchanged — widening the MCP surface is spec-060's
   audit territory.
5. **Pre-registration test-connection is deferred.** The mockup's probe-before-register
   (`mockup-compute-admin.html:556`) means probing a host the user does **not yet own** — an
   authenticated user could drive the server as an SSH prober against arbitrary hosts, outside the
   owner-scoped 404 discipline every machine route enforces (`MachineRS.java:22-24`). That
   security review doesn't fit here; the register-then-test flow (`app.js:566-575`) stays, upgraded
   to surface the new facts + latency immediately after register.
6. **Presentation: the Screen-C facts strip + handshake line, fed by the new fields.** 068 ships
   the strip and the last-probed/latency read-outs on the existing machine detail; full dashboard
   *placement* is 067's composition. Any styling forks stay in concern 064.

## Implementation

### Probe (server)

- New `HostFacts(osName, osVersion, kernel, arch, cores, memTotalBytes, uptimeSeconds)` record and
  `HostFactsProbe` beside `MachineFactsProbe` (same package, same `ssh.exec(target, argv, false)`
  read pattern, `MachineFactsProbe.java:88-101`). Parse: `PRETTY_NAME` (fallback `NAME` +
  `VERSION_ID`) from `/etc/os-release`; `uname -r` / `uname -m` verbatim; `nproc` as int;
  `MemTotal:` line of `/proc/meminfo` (kB → bytes); first field of `/proc/uptime` (seconds —
  parseable, unlike `uptime`'s locale-y prose). Every facet null on any failure.
- New `HostFactsRefresher` listener on `MachineReachedEvent`, mirroring the tagger's shape
  (`@Async("machineEventExecutor")`, guard read → probe outside tx → short write tx,
  `MachineFactsTagger.java:56-73`) but guarded by **staleness**, not once-ever:
  skip when `hostFactsAt` is younger than `ca.hostfacts.ttl` (default e.g. `PT1H`). The manual
  test endpoint refreshes unconditionally (operator asked).

### Latency / last-probed (server)

- Time the `true` exec in `ConnectionTestService.probe` (`ConnectionTestService.java:69-77`) and
  `ConnectivityCheckJob.probe` (`ConnectivityCheckJob.java:130-137`); persist
  `lastProbedAt`/`lastLatencyMs` in the existing short write transactions. These writes happen on
  **every** probe (unlike status, which stays write-on-change) — safe only because the columns are
  `@NotAudited`: no `machine_aud` revision, no `via=SYSTEM` noise, same treatment as
  `facts_probed_at` (`V8__machine_facts_probed_at.sql`).
- `ConnectionTestService.test` returns the fresh latency + facts alongside the observed status so
  the register flow's post-register toast/detail can show "handshake ok in N ms" without waiting
  for the async listener.

### Persistence

- One Flyway migration, `V16__host_facts_connectivity.sql` (V15 is current): nullable columns on
  `machine` — `os_name, os_version, kernel, arch, cores, mem_total_bytes, uptime_seconds,
  host_facts_at, last_probed_at, last_latency_ms`. All `@NotAudited` on the entity → **no**
  `machine_aud` columns (V8 precedent). Uptime is stored as the seconds observed at `hostFactsAt`;
  the UI renders it relative to now (`hostFactsAt − uptimeSeconds` = boot instant), so a stale
  probe still shows a truthful, growing uptime.

### DTO + presentation

- `MachineDtos.MachineView` (`MachineDtos.java:41-48`) gains
  `HostFactsView(osName, osVersion, kernel, arch, cores, memTotalBytes, uptimeSeconds, probedAt)`
  and `ConnectivityView(lastProbedAt, lastLatencyMs)`, both nullable-field-tolerant (an
  UNKNOWN-status machine has neither). `McpMachineView` untouched.
- `screenMachineDetail` (`app.js:607`) renders the facts strip under the page head
  (mockup `:792-799`; app.css gets the `.facts` idiom per the mocks-reuse-existing-design-system
  rule) and "last probed … · … ms" beside the status chip (mockup `:783-784`, `:915`). Facets
  render only when present — a null facet drops its cell, an all-null machine shows no strip.
- Register flow (`app.js:566-575`): after the existing post-register `/test`, show the returned
  facts + latency on landing (the Screen-A result, minus the pre-registration timing).

### Tests

Unit: `HostFactsProbe` parsing (quoted `PRETTY_NAME`, missing files → null facets, `MemTotal` kB
math, `/proc/uptime` fraction); refresher TTL guard (fresh skip / stale probe / manual bypass).
Integration (localssh profile, spec-003 pattern): a reach populates facts + `lastProbedAt`, an
unchanged re-probe writes no `machine_aud` revision. Web: `MachineView` carries both sub-records;
MCP list/read still serializes no facts field.

## Known Gaps

- **Spec-019 timestamps: verified, not reusable as-is.** `MachineReachedEvent.at` exists
  (`MachineReachedEvent.java:24`) and both write paths could stamp it — but nothing persists it
  today (`updatedAt` moves only on status change, `MachineStatusUpdater.java:61-64`) and no path
  measures duration. The event is the right trigger; the columns and timing are net-new.
- **Pre-registration test-connection is out (Decision 5).** If 064 resolves that the
  probe-before-register UX is required, it splits to its own spec with the security review
  (unowned-host probing, rate limiting, what the response may leak); this spec's endpoint surface
  stays owner-scoped.
- **Facts on MCP deferred to 060.** An LLM asking "how much RAM does web-prod-1 have" is a
  plausible tool need; widening `McpMachineView` is deliberately left to the MCP surface audit.
- **Linux-only parsing.** `/proc/meminfo`, `/proc/uptime`, `nproc` assume Linux; a BSD/mac target
  yields null facets and an empty strip — acceptable, same posture as every discoverer.
- **Uptime is not a liveness signal.** A rebooted-but-unprobed machine shows stale-derived uptime
  until the next reach; the TTL bounds the error. The cron cadence (`ca.connectivity.cron`,
  5 min default) bounds `lastProbedAt` staleness.
- **No mockup fork is decided here.** The facts strip itself is fork-free; anything Screen-C
  places *around* it (footprint bars vs 059 Decision 1, fonts, nav) belongs to concern 064.
