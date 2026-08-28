# 070 — SSH session reuse for the discovery probe path

Status: todo

## Context

Discovery intermittently fails with `{"error":"ssh_failed"}` and the monitor page
renders no CPU / memory / disk. Both symptoms are the **same defect**, and it is
**not** in the 055–063 app-model foundation — it is the spec-003 SSH executor
lifecycle, exposed now that we probe a **real, rate-limited host** under active
polling instead of a throwaway local sshd.

**Mechanism.** `MinaSshExecutor.run()` opens a **fresh TCP + SSH connect + auth for
every single `exec()` call** — no session reuse, no pool
(`src/main/java/com/iskeru/computeadmin/ssh/MinaSshExecutor.java:157`). Each probe
helper calls `exec` once (`Probes.commandExists` → `command -v`;
`Probes.lines` → the read), and `DiscoveryService.discover()` loops **8
discoverers**, most running a `command -v` guard plus one or more reads
(`DiscoveryService.java:153`). A single discovery of a single machine therefore
fires **~15–30 handshakes back-to-back** — which is also the ~2 connections/second
churn seen in the live log.

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
through, discovery finds the 7 real proposals (the cron-launched Spring Boot app is
discoverable via the port-based app-monitor). The pain is a burst **within one
operation**, not reconnection across operations — which is what makes the fix simple.

**Caller shape (why the blast radius is small).** The burst is concentrated in
discovery (`Probes`, `Cron/Database/Nginx` discoverers) and, to a lesser degree,
`MachineFactsProbe` (a handful of `cat`s). The single-shot callers
(`ConnectivityCheckJob`, `ConnectionTestService`, `ScriptPinService`) each issue one
`exec`. Runs go through `execStreaming` — one long command each. None of those burst.

## Decision

Two changes, shipped together:

1. **L1 — scoped session reuse (the root fix).** Add a `withSession` scope to the
   `SshExecutor` port. Open **one authenticated session per discovery pass** (and per
   `MachineFactsProbe` pass); run every probe as a fresh `ChannelExec` on that one
   session; close it in `finally`. Handshakes then scale with **machines, not
   probes**: one discovery = **one** handshake.

2. **L0 — degrade, don't abort.** Wrap each discoverer in the `DiscoveryService` loop
   so a single discoverer/probe failure **skips that family and marks the result
   partial**, instead of propagating out and zeroing the run. Independently valuable:
   even with reuse, one genuinely dead probe must not discard the other ~24.

**Explicitly deferred:** a long-lived **per-machine connection pool** (L2). The storm
is intra-operation, so a warm pool buys almost nothing here while adding a real
subsystem (keep-alive, idle eviction, health checks, reconnect, stale-session
detection, thread-safety). Deferred to a concern + the ARCH S-register — see Known
Gaps.

**Why scoped-session, not a pool.** Within one operation the probes run
**sequentially**, so at most **one channel is open at a time** — comfortably under the
remote `MaxSessions` (~10), needing no concurrency coordination — and the session
lives ~1–2s, so none of pooling's hard problems (idle eviction, stale detection,
reconnect) apply. L1 is the correct scaling, not a stopgap.

## Implementation

**Port (`ssh/SshExecutor.java`).** Add a scope method:

```java
<T> T withSession(SshTarget target, SessionWork<T> work);   // opens 1 session, closes in finally
interface SessionWork<T> { T run(SshSession session) throws IOException; }
interface SshSession { ExecResult exec(List<String> argv, boolean sudo); }  // opens a ChannelExec on the live session
```

Keep `exec()` and `execStreaming()`/`cancel()` for single-shot and run paths.

**`MinaSshExecutor`.** `withSession` performs the existing connect + `auth().verify()`
block **once**, builds an `SshSession` that opens a `ChannelExec` per `exec` on that
live `ClientSession`, and closes the session in `finally`. Refactor the current
`exec(target, argv, sudo)` to delegate — `withSession(target, s -> s.exec(argv, sudo))`
— so single-shot callers keep exactly one connection and there is a **single code
path**. Per-channel `execTimeout` and connect/auth `connectTimeout` semantics are
unchanged.

**`discovery/service/Probes.java`.** `commandExists` / `lines` take an `SshSession`
(not `SshExecutor` + `SshTarget`) and run on the open session.

**`RecipeDiscoverer` contract + all 8 impls.** Change
`discover(Machine, SshExecutor)` → `discover(Machine, SshSession)`. The change is
mechanical — every discoverer already delegates to `Probes` / `ssh.exec`.
(`AppMonitorDiscoverer`, `DockerDiscoverer`, `DockerComposeDiscoverer`,
`SystemdDiscoverer`, `NginxDiscoverer`, `DatabaseDiscoverer`, `CronDiscoverer`,
`MonitorMachineDiscoverer`.)

**`discovery/service/DiscoveryService.discover()`.** Wrap the loop:

```java
List<ProposedRecipe> proposals = new ArrayList<>();
List<DiscovererFamily> failed = new ArrayList<>();
ssh.withSession(target, session -> {
    for (RecipeDiscoverer d : discoverers) {
        if (!enabled.contains(d.family())) continue;
        try {
            proposals.addAll(d.discover(machine, session));   // L1: one open session
        } catch (SshExecutionException e) {                   // L0: degrade
            log.warn("discovery: family {} failed on {}", d.family(), machineId, e);
            failed.add(d.family());
        }
    }
    return null;
});
```

Surface `failed` on the discovery result (a `partial` flag + the failed-family list on
the `DiscoveryResult` DTO) so the UI can show "some families could not be probed"
rather than silent emptiness. Keep the existing `MachineReachedEvent` publish (the box
was reached).

**`MachineFactsProbe`.** Route its multiple `cat` reads through `withSession` too
(small burst; cheap win). In scope.

**Adapters.** `LocalDevSshExecutor` and `CannedSshExecutor` implement `withSession`
trivially — call `work` with an `SshSession` that delegates to their existing `exec`.
Keeps `localssh` / `demo` profiles and all tests working.

**Untouched.** `execStreaming` / `cancel` / the run path (one long command, no burst);
`ConnectivityCheckJob`, `ConnectionTestService`, `ScriptPinService` (single-shot);
`assembleCommand` / single-quoting (S4); the approval gate.

**Tests.**
- `MinaSshExecutor`: N `exec` calls inside one `withSession` open **exactly one**
  `ClientSession` / one auth (assert against a counting client, or count auth events on
  the throwaway sshd container from the CLAUDE.md verify recipe).
- `DiscoveryService`: one discoverer throwing `SshExecutionException` still returns the
  other discoverers' proposals and flags the failed family (`partial == true`).
- Regression: existing discovery tests pass unchanged through the session-scoped path.

## Known Gaps

- **L2 per-machine connection pool — deferred (own concern + ARCH S-register).** Reuse
  *across* separate operations (a warm pool keyed by `machineId` with keep-alive, idle
  eviction, health checks, reconnect backoff, stale-session detection, thread-safety).
  Not needed for this symptom (the storm is intra-operation) and materially more
  complex. **Revisit trigger:** monitor sampling moving to sub-minute cadence across
  many hosts, or profiling showing per-operation handshake latency dominating.
- **Concurrent probing on one session** is out of scope — v1 stays sequential (simpler,
  `MaxSessions`-safe). Bounded-concurrency channels on the single session is the next
  lever if discovery latency matters — before a pool.
- **`command -v ss` non-interactive PATH.** Whether `ss` (often `/usr/sbin`) resolves in
  the login user's non-interactive shell is a **separate** probe-robustness question
  from connection reuse; not addressed here.
- **Monitor sampling path.** This spec fixes discovery + facts. If host-vitals sampling
  bursts SSH outside the discovery path, it must adopt `withSession` too — the builder
  confirms the sampling path and routes it through the same scope.
