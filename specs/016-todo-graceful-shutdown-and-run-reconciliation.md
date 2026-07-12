# 016 — Graceful shutdown & run reconciliation

> **Status: todo.** Linear is blocked for this repo, so this spec carries no issue
> id; it will be built and committed under `spec-016` subjects. Touches `config`,
> `run`, `ssh`, and app configuration — all merged on `main`.

## Context

compute-admin has **essentially no graceful shutdown**, and there is **no
reconciliation on boot**. On `SIGTERM`/`Ctrl-C` an in-flight run is abandoned and
leaves a permanently-stuck DB row that no later event ever resolves. This is a
High-severity correctness bug: a user or agent polling `get_run` (REST
`GET /api/runs/{id}` — `run/api/RunRS.java:60`, or MCP `get_run`) sees a run that
**never** reaches a terminal state.

### How a run reaches a stuck row

The run engine is fully async (spec-005):

1. `RunService.run(...)` persists the row `QUEUED` inside `@Transactional`
   (`run/service/RunService.java:98`, status set at `:130`) and, **after commit**,
   submits `execute(runId, …)` onto the `runExecutor` pool
   (`submitAfterCommit`, `:134` / `:251`).
2. `execute(...)` first calls `markRunning(runId)` — flips the row to `RUNNING`
   and sets `startedAt` (`:193`, `:226`) — then calls
   `ssh.execStreaming(...)` (`:218`).
3. Only `finish(...)` writes a terminal `DONE`/`FAILED` with the exit code
   (`:234`, status decided at `:239`); the `catch` at `:219` records `FAILED` on a
   transport error.

The pool that runs steps 2–3 is defined in `config/AsyncConfig.java`: core `2` /
max `8` / queue `100` (`:30–32`, `:41–43`), **`setDaemon(true)`** (`:47`), and it
relies on `ThreadPoolTaskExecutor`'s default
`waitForTasksToCompleteOnShutdown=false`. The in-code comment at `AsyncConfig.java:45`
names this the deferred seam ("graceful drain of in-flight runs is a v-next concern
alongside S7").

### The concrete stuck-row scenarios

On `SIGTERM`, Spring Boot's default shutdown hook closes the context. Because the
pool is daemon and is **not** awaited (`waitForTasksToCompleteOnShutdown=false` ⇒
`ThreadPoolTaskExecutor` calls `shutdownNow()` and returns without awaiting):

- **Stuck `RUNNING`.** A run already past `markRunning` but whose `finish()` has not
  executed. Its worker thread is a daemon that dies with the JVM; `finish()` never
  runs. The row stays `RUNNING` **forever**. This is made worse by
  `ssh/MinaSshExecutor.java:96` — the `@PreDestroy shutdown()` calls
  `client.stop()` on the **shared** `SshClient` (spec-013), which can race and kill
  the in-flight session out from under the worker.
- **Stuck `QUEUED`.** A run still sitting in the pool's queue (up to 100). `shutdownNow()`
  drops the queue; the task never runs. The row stays `QUEUED` **forever**.
- **No reconciler.** `RunStatus` (`run/model/RunStatus.java`) has only `QUEUED`,
  `RUNNING`, `DONE`, `FAILED` — no interrupted/unknown state — and nothing on boot
  re-examines non-terminal rows, so the stuck row is permanent.

There is also **no HTTP-level graceful shutdown**: `application.yml` /
`application-dev.yml` set **no** `server.shutdown` and **no**
`spring.lifecycle.timeout-per-shutdown-phase`, so Tomcat is stopped abruptly and any
open SSE output stream (`RunRS.output`, `run/api/RunRS.java:66`) is cut mid-flight.
`Application.java:16` is a bare `SpringApplication.run(...)` (only Boot's default
shutdown hook). There is **no Actuator dependency** in `pom.xml` (confirmed — deps
are web / resteasy / mcp / data-jpa / jjwt / spring-security-crypto / envers /
sshd-core / flyway / h2 / lombok / test), so no readiness-probe machinery exists.

### Single-instance assumption (load-bearing)

The datasource is an H2 **file** DB with `AUTO_SERVER=TRUE`
(`application.yml:9`) and `ddl-auto=none` + Flyway. `AUTO_SERVER` allows extra
**connections** to the same files but there is still exactly **one owning
application process**. A boot reconciler that sweeps *every* non-terminal run is
therefore safe: any `QUEUED`/`RUNNING` row observed at startup necessarily belongs
to the **previous, now-dead** process — never to a live peer. This spec assumes and
records that single-instance invariant; it must be revisited if compute-admin ever
runs multi-instance against a shared DB.

### Neighbor relationship to S7 (kept out of scope)

The run pool in `AsyncConfig` is the same seam where the **S7** per-machine
concurrency cap / global run quota will land (ARCH.md S-register; noted in
`AsyncConfig.java:45` and the spec-013 known gaps). This spec touches the same
class, so the two are neighbors — but S7 (rate limiting / concurrency cap) stays
**out of scope** here. This spec is purely about lifecycle correctness.

## Options considered

Both options share one non-negotiable component: **a boot-time reconciler is the
authoritative fix** — it is the only mechanism that *guarantees* no permanently
non-terminal row, regardless of how shutdown went. The options differ only in
whether we *also* try to drain in-flight runs at shutdown.

### Option A — Bounded graceful drain + boot reconciler (backstop)

At shutdown, drain in-flight HTTP and in-flight runs up to a bounded timeout, then
let the boot reconciler mop up anything that did not finish in time.

- Enable `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase`
  so Tomcat stops accepting new requests and drains in-flight HTTP/SSE before the
  context tears down.
- Make `runExecutor` participate: `setWaitForTasksToCompleteOnShutdown(true)` +
  `setAwaitTerminationSeconds(N)`, keeping `setDaemon(true)`. Daemon **+** bounded
  await is the balanced choice: the pool awaits in-flight tasks for up to `N`
  seconds, and if any run exceeds `N`, the daemon threads are abandoned at JVM exit
  (shutdown never hangs).
- Order `@PreDestroy` so the pool drains **before** `MinaSshExecutor` stops the
  shared client, so a draining run isn't killed mid-session.
- Boot reconciler sweeps whatever is still non-terminal (runs that exceeded the
  timeout, or `QUEUED` rows never started).

*Trade-offs.* **+** A run that finishes within `N` seconds lands with its *real*
`DONE`/`FAILED` outcome and full captured output, so fewer runs are falsely marked
interrupted; SSE clients get a clean close instead of a dropped socket. **−** More
moving parts (await config + `@PreDestroy` ordering + the queue-drain semantics
below); shutdown latency grows by up to `N` seconds. **Caveat:**
`waitForTasksToCompleteOnShutdown(true)` makes the pool do an *orderly*
`shutdown()`, which also **drains the queue** — so `QUEUED` runs may *start* during
shutdown rather than being dropped. That is acceptable (they either finish within
the window or the reconciler catches them), but it means the drain is not a pure
"finish active only" and the timeout must be modest.

### Option B — Fail-fast + boot reconciler only

Don't try to drain runs at shutdown at all. Keep the daemon pool as-is; on
`SIGTERM` in-flight and queued runs are abandoned. The **only** correctness
mechanism is the boot reconciler, which reliably marks every orphaned
`QUEUED`/`RUNNING` row terminal on next boot. (Optionally still enable
`server.shutdown: graceful` for HTTP/SSE, since that is independent and cheap.)

*Trade-offs.* **+** Much simpler — no await tuning, no `@PreDestroy` ordering, no
queue-drain subtlety; fast, deterministic shutdown. Still fully fixes the
user-visible bug (no permanently-stuck rows). **−** Every run in flight at shutdown
is marked interrupted even if it was milliseconds from completing (and even if the
remote command actually did complete on the box), losing the real outcome and any
tail of output; open SSE streams are cut abruptly unless graceful HTTP is added.

## Decision

**Adopt Option A — bounded graceful drain with the boot reconciler as the
authoritative backstop.** The reconciler is the load-bearing correctness fix and is
present in both options; the incremental cost of the bounded drain over Option B is
small (a handful of config keys plus explicit bean ordering), and it buys real
correctness: runs that finish within the window record their *true* outcome instead
of a blanket "interrupted", and SSE clients close cleanly. Keeping the pool
`daemon` **and** bounding the await means shutdown can never hang — the drain is
strictly best-effort and the reconciler is what makes the guarantee. The drain
window is deliberately short so shutdown stays snappy.

Introduce a new terminal status **`RunStatus.INTERRUPTED`** for rows the reconciler
resolves (see rationale below) — it is more honest than overloading `FAILED`
(which means "the command ran and exited non-zero / the transport errored").

## Implementation

All new tunables use the existing `ca.*` namespace via constructor `@Value` with
in-code defaults (ARCH.md convention); framework beans stay in `config/`.

### 1. HTTP graceful shutdown (`application.yml`)

Add, at the base config level so all profiles inherit:

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 25s
```

Tomcat's `WebServerGracefulShutdownLifecycle` then stops accepting new connections
and lets in-flight requests — including the `RunRS.output` SSE stream — finish or
hit the phase timeout before the context closes. No Actuator is added; readiness/
liveness probes are explicitly **not** in scope (they fold into the S1' transport
hardening spec).

### 2. Run pool participates in shutdown (`config/AsyncConfig.java`)

On the `runExecutor` `ThreadPoolTaskExecutor`:

- `setWaitForTasksToCompleteOnShutdown(true)`
- `setAwaitTerminationSeconds(...)` — new key `ca.run.shutdown-await-seconds`
  (default `20`, kept below the 25s phase timeout so the await completes within the
  shutdown phase).
- **Keep `setDaemon(true)`** — with a bounded await this is the balanced choice:
  drain for the window, then give up at JVM exit rather than hang.

Update the `AsyncConfig.java:45` comment (which currently defers the drain) to point
at this spec.

### 3. `@PreDestroy` ordering — drain runs before killing the SSH client

The pool must finish draining **before** `MinaSshExecutor.shutdown()`
(`ssh/MinaSshExecutor.java:96`) calls `client.stop()`, otherwise a still-draining
run has its shared session torn out from under it. Two mechanisms were analyzed:

- **`@DependsOn` (recommended).** Annotate the `runExecutor` bean
  `@DependsOn("minaSshExecutor")`. Spring destroys a bean **before** its
  dependencies, so the pool (whose destroy awaits in-flight runs) is torn down
  first, and only then is `MinaSshExecutor` destroyed and the client stopped. This
  is semantically true anyway — the run engine uses the SSH port. Simple, local,
  one annotation. (Guard for the `localssh` profile, where the bean is
  `LocalDevSshExecutor`, not `minaSshExecutor` — depend on the port bean name that
  is actually present, or make the dependency profile-aware.)
- **`SmartLifecycle` phase (alternative).** Model the shared `SshClient` as a
  `SmartLifecycle` whose `stop()` runs in a phase that stops *after* the run pool's
  phase. More explicit about ordering and composes with the web-server graceful
  phase, but it is more machinery than the problem needs and reworks the spec-013
  client-lifecycle design. Rejected as the primary in favor of `@DependsOn`; keep
  it in reserve if ordering needs ever grow.

Note the drain-then-stop ordering only *reduces* spurious interrupts; the reconciler
still backstops any run that exceeds the await window.

### 4. Boot reconciler (`run` module) — the authoritative fix

Add a `RunReconciler` in `run/service` invoked once on startup via
`@EventListener(ApplicationReadyEvent)` (or an `ApplicationRunner`). On boot it:

- loads every run whose status is `QUEUED` or `RUNNING` (see repository finder
  below);
- marks each terminal: `status = INTERRUPTED`, `finishedAt = Instant.now()`,
  `exitCode = -1` (matching the existing "-1 = no code / transport error"
  convention on `Run.exitCode`, `run/model/Run.java:90`), and appends a sentinel
  note to `stderr` such as *"Run abandoned by a server shutdown; the remote
  command's actual outcome is unknown."*;
- logs a one-line summary (count reconciled) for operability.

Because the rows are all from the dead previous process (single-instance
invariant), this is unconditional — no "is it really orphaned?" check is needed.
`Run` is **not** `@Audited` (`run/model/Run.java:27`), so the reconciler's writes
need no ambient actor for Envers; running it under
`CurrentUser.runWhere(AuthContext.system(), …)` (`common/CurrentUser.java:83`) is
optional and only for consistency, not correctness.

**Repository finder** (`run/repository/RunRepository.java`): add a derived query
`List<Run> findByStatusIn(Collection<RunStatus> statuses)` (owner-bypassing by
design — reconciliation is system-scoped across all users, mirroring
`ConnectivityCheckJob`'s `findAll()` fleet sweep).

### 5. `RunStatus.INTERRUPTED` — enum change, **no migration needed**

`Run.status` is `@Enumerated(EnumType.STRING)` (`run/model/Run.java:86`) mapped to
`status VARCHAR(20) NOT NULL` with **no `CHECK` constraint**
(`db/migration/V5__run.sql:21`). A new enum value is stored as its name string and
fits in 20 chars, so **adding `INTERRUPTED` requires no Flyway migration** — the
column already accepts any ≤20-char string. (Had the column used `ORDINAL` or a DB
`CHECK`/enum type, a migration would be required; it does not.)

Add the value to `run/model/RunStatus.java` with a Javadoc line marking it terminal
and citing `spec-016`, and update the enum's class Javadoc (which currently says a
run "lands on a terminal `DONE`/`FAILED`").

### 6. Delivery-pool bound (`run/service/RunOutputHub.java`)

The SSE/MCP output fan-out pool is an **unbounded** `newCachedThreadPool`
(`RunOutputHub.java:120`), `@PreDestroy shutdownNow()` at `:132`. Replace it with a
**bounded** executor so a burst of subscribers can't spawn unbounded threads:
a fixed pool sized by a new key `ca.run.output-delivery-threads` (default e.g. `8`),
keeping the daemon thread factory (`:123`) and the `@PreDestroy` shutdown. The hub's
existing per-subscriber backlog drop (`ca.run.output-subscriber-backlog`, spec-013)
already bounds memory per channel; this bounds the *thread* dimension. Its
`@PreDestroy` should run after the run pool drains (same ordering concern; it is in
the `run` module and naturally later than the SSH client, but verify at build time).

### Blast radius — every code point that must change or be checked

- `config/AsyncConfig.java` — await flags + `@DependsOn` + comment (§2, §3).
- `application.yml` — `server.shutdown` + `spring.lifecycle.timeout-per-shutdown-phase`
  (§1).
- `run/model/RunStatus.java` — new `INTERRUPTED` value + Javadoc (§5).
- `run/service/RunService.java:162` — `subscribeToOutput` currently treats only
  `DONE`/`FAILED` as terminal; **must also treat `INTERRUPTED` as terminal** so a
  post-reconcile subscribe replays from the persisted `Run` instead of creating a
  live channel that hangs. The class Javadoc at `:53–56` (lands on `DONE`/`FAILED`)
  should be updated too.
- `run/service/RunReconciler.java` — new (§4).
- `run/repository/RunRepository.java` — new `findByStatusIn` finder (§4).
- `run/service/RunOutputHub.java` — bound the delivery pool (§6).
- `ssh/MinaSshExecutor.java:96` — no code change, but its `@PreDestroy` ordering
  relative to the run pool is the thing §3 fixes.
- `src/main/resources/static/app.js:189–192` — the status→badge-color map has no
  `INTERRUPTED` entry; add one (map to `bad`, or a distinct warn tone if the design
  system gains one) so the UI badge renders. `tokens.css:29–32` documents the
  existing status colors; extend the comment if a new tone is introduced.
- `run/api/RunDtos` (`RunView`) — serializes `status` as the enum name, so
  `INTERRUPTED` flows through with no DTO change; confirm no client switch statement
  assumes a closed set.

### Tests

- `RunReconcilerTest` (`@DataJpaTest` slice + `@Import`): seed `QUEUED` and
  `RUNNING` rows, run the reconciler, assert both are `INTERRUPTED` with
  `finishedAt` set, `exitCode = -1`, and the sentinel note on `stderr`; a
  pre-existing `DONE`/`FAILED` row is left untouched.
- `RunServiceTest` (extend): after reconciling a run to `INTERRUPTED`,
  `subscribeToOutput` replays the persisted output + `onComplete` (does **not**
  hang on a fresh live channel) — the direct regression for the `:162` terminal
  check.
- `AsyncConfig` await wiring: assert the `runExecutor`'s
  `waitForTasksToCompleteOnShutdown` and `awaitTerminationSeconds` are set from the
  `ca.run.*` keys (a small context test).
- A drain integration check (best-effort): submit a slow run, close the context,
  assert the run reached a terminal state (either drained `DONE`/`FAILED` within the
  window, or `INTERRUPTED` after boot) — the end-to-end proof that no row stays
  non-terminal.

## Known gaps

- **S7 (run rate-limiting / per-machine concurrency cap / global quota)** stays
  **out of scope** — it shares the `AsyncConfig` seam but is an orthogonal concern.
- **Readiness/liveness probes & Actuator** are **not** added; graceful HTTP
  shutdown here is the Tomcat lifecycle only. Probe-driven rollout folds into the
  **S1'** transport-hardening spec.
- **Multi-instance** operation is explicitly unsupported: the boot reconciler's
  "sweep all non-terminal rows" is correct only under the single-instance
  `AUTO_SERVER` invariant. If compute-admin is ever run multi-instance, the sweep
  must become process-scoped (e.g. an owning-instance id / lease per run).
- The reconciler records `INTERRUPTED` with `exitCode = -1`; it **cannot** know
  whether the remote command actually completed on the target box (SSH gave no exit
  status back before the process died). The sentinel note states this; there is no
  attempt to re-probe the target for the real outcome.
- The bounded drain is **best-effort**: a run exceeding `ca.run.shutdown-await-seconds`
  is still abandoned and left to the reconciler — by design, so shutdown never
  hangs.
