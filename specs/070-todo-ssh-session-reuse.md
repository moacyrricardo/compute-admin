# 070 — SSH session reuse for the discovery probe path

Status: todo

## Context

Discovery intermittently fails with `{"error":"ssh_failed"}` even against a reachable
host. This spec fixes **that** — the discovery probe path — and nothing else. (The
sibling symptom the same host showed, a monitor page rendering no CPU / memory / disk,
turns out to be a **different** code path — the run/poll fan-out — and is split into
its own **concern 071**; see Known Gaps. The two interact: the monitor path's sustained
connection churn plausibly trips the same host-side rate-limit that then also refuses
discovery's probes, so 071 is the higher-impact fix. But they are distinct defects with
distinct fixes.)

This is the spec-003 SSH executor lifecycle, exposed now that we probe a **real,
rate-limited host** instead of a throwaway local sshd — not a regression from the
055–063 app-model foundation.

**Mechanism.** `MinaSshExecutor.run()` opens a **fresh TCP + SSH connect + auth for
every single `exec()` call** — no session reuse, no pool
(`src/main/java/com/iskeru/computeadmin/ssh/MinaSshExecutor.java:158`). Each probe
helper calls `exec` once (`Probes.commandExists` → `command -v`; `Probes.lines` → the
read), and `DiscoveryService.discover()` loops **8 discoverers**, most running a
`command -v` guard plus one or more reads (`DiscoveryService.java:153`). A single
discovery of a single machine therefore fires **~15–30 handshakes back-to-back**.

**Why it fails.** The remote sshd rate-limits the burst (`MaxStartups`, and possibly
fail2ban) and refuses a fraction of the connections. A refused connection throws
`IOException` → `SshExecutionException` (`SshExecutionException.java:23`, mapped to
`BAD_GATEWAY` `ssh_failed`). The discoverer loop has **no per-probe error handling**,
so the first refusal propagates straight out and **aborts the entire run**, discarding
every proposal that would have succeeded.

**Evidence (reproduced live against `ubuntu@201.23.87.96:22`, read-only probes):**

| Test | Connections | Result |
|---|---|---|
| `POST /machines/{id}/test` | 1 | `ONLINE` — every time |
| 20× concurrent `/test` | 20 burst | **16 ONLINE, 4 UNREACHABLE** (~20% refused) |
| `POST /machines/{id}/discover` ×3 | 15–30 burst each | **`OK — 7 proposals` → `ssh_failed` → `ssh_failed`** |

Auth is never the problem (single connections always succeed); the only variable that
flips the outcome is **how many connections are opened at once**. When the burst gets
through, discovery finds the 7 real proposals. The pain is a burst **within one
operation** — which is what makes this fix simple and self-contained.

**Caller shape (why the blast radius is small).** The bursty SSH paths are: discovery
(`Probes`, `Cron/Database/Nginx` discoverers — this spec) and the **monitor run/poll**
path (`RunService` `APP_PORT_LIST` fan-out onto the `runExecutor` pool, many concurrent
`execStreaming` connections every 5–30s — **concern 071**, out of scope here). The
single-shot callers (`ConnectivityCheckJob`, `ConnectionTestService`,
`ScriptPinService`) each issue one `exec`. `MachineFactsProbe` does a handful of `cat`s
but only **once per machine ever** (guarded by `factsProbedAt`), so it never
contributed to the observed churn — in-scope but minor.

## Decision

Two changes, shipped together, scoped to the **discovery** probe path:

1. **L1 — scoped session reuse (the root fix).** Add a `withSession` scope to the
   `SshExecutor` port. Open **one authenticated session per discovery pass** (and per
   `MachineFactsProbe` pass); run every probe as a fresh `ChannelExec` on that one
   session; close it in `finally`. Handshakes then scale with **machines, not probes**:
   one discovery = **one** handshake.

2. **L0 — degrade, don't abort.** Wrap each discoverer in the `DiscoveryService` loop
   so a single discoverer/probe failure **skips that family and marks the result
   partial**, instead of propagating out and zeroing the run.

**Explicitly deferred:** a long-lived **per-machine connection pool** (L2). Note its
revisit trigger ("sub-minute sampling cadence across many hosts") is *already met* — but
by the **monitor run/poll** path, which is **concern 071**, not this spec. The
discovery burst here is intra-operation and needs no pool.

**Why scoped-session, not a pool.** Within one discovery the probes run **sequentially**
(a plain `for` loop, no fan-out), so at most **one channel is open at a time** —
comfortably under the remote `MaxSessions` (~10), needing no concurrency coordination —
and the session lives ~1–2s, so none of pooling's hard problems apply.

## Implementation

**Port (`ssh/SshExecutor.java`) — a `default` method, so no adapter or fake changes.**

```java
// default on the port: open one session, run the work, close in finally.
// The DEFAULT delegates to per-call exec() (a session that reconnects each exec),
// so LocalDevSshExecutor / CannedSshExecutor / FakeSshExecutor / StubSshExecutor
// need ZERO code. Only MinaSshExecutor OVERRIDES it to actually reuse one session.
default <T> T withSession(SshTarget target, SessionWork<T> work) {
    return work.run(SshSession.of(this, target));   // bridge: exec() per call
}
interface SessionWork<T> { T run(SshSession session); }
interface SshSession {                              // opens a ChannelExec on the live session
    ExecResult exec(List<String> argv, boolean sudo);
    static SshSession of(SshExecutor ex, SshTarget t) { return (argv, sudo) -> ex.exec(t, argv, sudo); }
}
```

**`MinaSshExecutor` — override `withSession` only.** Perform the existing connect +
`auth().verify()` block **once**, build an `SshSession` that opens a `ChannelExec` per
`exec` on that live `ClientSession`, and close the session in `finally`. **Only the
buffered `exec()` benefits;** leave the `run(...)` overload that carries the streaming
`out/err`, the `cancelKey`, and the `cancellable` map **exactly as is** — `execStreaming`
/ `cancel` (spec-026 follow-mode) is single-shot and long-lived by design and keeps its
own connect. (So "one code path" applies only to buffered `exec`, not to streaming.)

**Transport-failure wrapping (contract).** `withSession` and `SshSession.exec` must wrap
**every** transport failure into `SshExecutionException` — not only checked `IOException`
but MINA's **unchecked** `RuntimeSshException` from connect/channel paths — so the L0
catch below is complete and a rate-limit refusal never escapes as a raw 500.

**`discovery/service/Probes.java`.** `commandExists` / `lines` take an `SshSession` (not
`SshExecutor` + `SshTarget`) and run on the open session.

**`RecipeDiscoverer` contract + all 8 impls.** Change
`discover(Machine, SshExecutor)` → `discover(Machine, SshSession)`. Mechanical — every
discoverer already delegates to `Probes` / `ssh.exec`. This is a **compile-breaking
signature migration**, so it ripples into the discoverer tests (below).

**`discovery/service/DiscoveryService.discover()`.** Wrap the loop; catch **narrowly**:

```java
List<ProposedRecipe> proposals = new ArrayList<>();
List<DiscovererFamily> failed = new ArrayList<>();
boolean connectionLost = false;
try {
    ssh.withSession(target, session -> {
        for (RecipeDiscoverer d : discoverers) {
            if (!enabled.contains(d.family())) continue;
            try {
                proposals.addAll(d.discover(machine, session));   // L1: one open session
            } catch (SshExecutionException e) {                   // L0: degrade — transport only
                log.warn("discovery: family {} could not probe {}", d.family(), machineId, e);
                failed.add(d.family());
            }
            // NB: a non-SshExecutionException (e.g. a discoverer NPE) is NOT caught here —
            // it aborts loudly, as it should. Only transport failures degrade.
        }
        return null;
    });
} catch (SshExecutionException e) {           // connect/auth failed BEFORE the loop → real outage
    connectionLost = true;
}
```

**Session-death coherence (finding #4).** A transport failure *mid-pass* usually means
the one session died, after which every remaining family would log an identical failure.
So: once a probe throws `SshExecutionException` mid-pass, **stop iterating** (the session
is likely dead) and surface **"connection lost mid-discovery"** — distinct from an
honest per-family failure — rather than emitting 5–6 misleading "family failed" entries.
And **only publish `MachineReachedEvent` if at least one probe actually succeeded** (do
not announce ONLINE off a run that connected then dropped).

**Result shape + full blast radius (finding #2).** `DiscoveryService.discover` currently
returns `List<DiscoveredRecipe>`; the `partial` flag + failed-family list needs a small
result wrapper. That ripples to: `DiscoveryDtos` (a `partial` + `failedFamilies` on the
result), `DiscoveryRS`, the **MCP `DiscoverRecipesTool`** (its summary must surface
`partial` — else MCP callers silently never learn a family was skipped), and **`app.js`**
(the discovery flow renders "some families could not be probed"). Enumerate all four in
the build.

**Tests (finding #6 — use the pattern the repo already has).** Pin behavior with the
in-process `SshServer` harness from `MinaSshExecutorRealSshTest` (a mock client cannot
even complete `connect()` — see `MinaSshExecutorTest`):
- A **counting `PublickeyAuthenticator` + counting `CommandFactory`**: assert N commands
  inside one `withSession` cause **exactly 1 auth**; and, as regression, **N** auths for
  **N** bare `exec()` calls.
- `DiscoveryService`: one discoverer throwing `SshExecutionException` still returns the
  others' proposals and flags the family (`partial == true`); a discoverer throwing a
  non-transport exception still aborts.
- The existing discoverer tests **compile-migrate** to the `SshSession` signature (a
  one-line change each via `SshSession.of(fake, target)`); reword the old "pass
  unchanged" claim to "pass after the mechanical signature migration".

## Known Gaps

- **Monitor run/poll burst → concern 071 (companion).** The blank-vitals symptom is the
  monitor UI polling by POSTing runs: `RunService` fans out one child run per
  `APP_PORT_LIST` item onto the `runExecutor` pool, each an independent `execStreaming`
  connection, 4 host-vitals polls + a consumer fan-out per machine every 5–30s. That is a
  **cross-operation** burst (independent HTTP-triggered runs on a thread pool) that
  `withSession` cannot scope. It needs its own decision — batched host-vitals, a shared
  session across a poll cycle, or the L2 pool — hence concern **071**.
- **L2 per-machine connection pool — deferred.** Reuse across separate operations
  (warm pool keyed by `machineId`: keep-alive, idle eviction, health checks, reconnect,
  stale-detection, thread-safety). It is one candidate answer to 071; not needed for
  discovery's intra-operation storm. Cross-reference into ARCH.md's S-register.
- **Concurrent probing on one session** is out of scope — v1 stays sequential (simpler,
  `MaxSessions`-safe). Bounded-concurrency channels on the single session is the next
  lever if discovery latency matters — before a pool.
- **`command -v ss` non-interactive PATH.** Whether `ss` (often `/usr/sbin`) resolves in
  the login user's non-interactive shell is a **separate** probe-robustness question from
  connection reuse; not addressed here.
