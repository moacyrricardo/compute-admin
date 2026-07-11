# 005 — Execution engine (async jobs + live streaming)

**Status:** done
**Branch:** `moacyrricardo/spec-005-execution-engine`
**Linear:** none — Linear is BLOCKED for this repo (see CLAUDE.md); tracked as `spec-005`.

## Context

With the gate (004) and the SSH adapter (003) in place, this spec runs approved
actions as **asynchronous jobs** with **live-streamed** output to the UI (SSE)
and MCP (progress), a captured exit code, and an append-only record. It is the
**single shared entry point** both the UI and the MCP `run_action` tool (008)
call — the gate is enforced in exactly one place.

## Decision

One `RunService.run` entry point re-checks approval **and the content hash** at
execution time, binds params via the 004 `ParamBinder`, and hands discrete argv
to `SshExecutor`. Output streams live and is persisted on an append-only `Run`
that records exactly what ran under which approved definition.

## Implementation

**`run/model/Run`** — append-only, **not** `@Audited`:
- `String id`; `@ManyToOne Action action`; `@ManyToOne Machine machine`;
  `String callerUserId` + `Via via` (UI/MCP — who ran it);
  `String paramsJson` (supplied params snapshot);
  `String resolvedArgvJson` (the exact argv executed); `String
  approvedSnapshotHash` (hash in force at run time); `RunStatus status`
  (`QUEUED | RUNNING | DONE | FAILED`); `Integer exitCode` (nullable);
  `@Lob String stdout`, `@Lob String stderr` (appended as chunks arrive);
  `Instant createdAt/startedAt/finishedAt`.

**`run/service/RunService.run(machineId, actionId, params)`** — the gate:
1. `requireMachine` (003) and `requireAction` (004) — both scoped to
   `CurrentUser.require()`, so a user can only run against **his own** machines
   (cross-user/missing → 404).
2. Assert `action.approvalState == APPROVED`, else `ActionNotApprovedException`.
3. Assert `ActionSnapshot.hash(action) == action.approvedSnapshotHash`, else
   `ActionModifiedException` (defends the 004 binding at run time).
4. `ParamBinder.bind(action, params)` → argv (throws `ParamValidationException`).
5. Persist `Run(QUEUED, callerUserId=CurrentUser.require(), via=CurrentUser.via(),
   resolvedArgvJson, approvedSnapshotHash)` (`@Transactional`), return its id.
6. Submit to the async executor; the task sets `RUNNING`, calls
   `SshExecutor.execStreaming` with an `OutputSink`, and on completion sets
   `exitCode` + `DONE`/`FAILED` (its own tx).

**Streaming (`run/service/RunOutputHub`).** Per-run fan-out: the `OutputSink`
pushes stdout/stderr chunks to (a) SSE subscribers, (b) any MCP progress consumer
(008), and (c) the persisted `Run` (append). Late subscribers get the buffered
prefix then the live tail. Backed by the `config/AsyncConfig`
`ThreadPoolTaskExecutor` (bounded core/max/queue).

**`run/api/RunRS`** (`@Path("/runs")`, `@Secured`) — all endpoints scope to the
current user's runs:
- `POST /` — body `{machineId, actionId, params}` → `RunDtos.RunView` (runId +
  status). Used by the UI.
- `GET /{id}` → `RunView` (status, exitCode, timestamps).
- `GET /{id}/output` — `text/event-stream`; hand-built streaming `Response` via
  JAX-RS `Sse`/`SseEventSink` (an allowed exception to "resources return DTOs").
- `RunDtos`: `RunRequest`, `RunView.of(Run)`.

**`run/repository/RunRepository`.** Migration `V5__run.sql` (`run` table, no
`_aud`).

**Tests.**
- `RunServiceTest` (`@DataJpaTest` slice + `@Import`): refuses non-APPROVED (→
  `ActionNotApprovedException`); refuses a mutated-since-approval action (→
  `ActionModifiedException`); invalid params (→ `ParamValidationException`);
  happy path records `resolvedArgvJson` + `exitCode`. Uses a fake `SshExecutor`.
- `RunWebTest` (`@SpringBootTest RANDOM_PORT`): `POST /api/runs` then consume
  `GET /{id}/output` SSE to completion, against the Docker sshd container (or a
  fake `SshExecutor` `@Primary` bean).

## Known Gaps

- **S7 — no rate limiting / concurrency cap**, and **no per-machine
  serialization**: concurrent runs mutating the same service (two `restart
  nginx`) can interleave. Add a per-machine cap + global quota when more than one
  agent can drive it.
- **Output is stored and streamed unredacted.** Service commands often print
  secrets (env, connection strings, `docker inspect`); the `Run` record and the
  unencrypted H2 DB then hold them. No redaction/retention policy in v1.
- Output is scoped to the owning user (011), but there is no finer-grained access
  control within a user's own runs.

## Implementation Notes

Built as specified — single-gate `RunService.run`, append-only `Run`, `V5__run.sql`
(no `_aud`), the bounded `config/AsyncConfig` `runExecutor` pool, `RunOutputHub`
fan-out with buffered-prefix replay, and the `RunRS` REST surface with the SSE
output exception. Deltas from the spec text:

- **Return type.** `RunService.run(...)` returns the persisted `Run` entity (not the
  id); `RunRS` maps it to `RunDtos.RunView`. Same information, DTO conversion kept at
  the edge.
- **Cross-machine guard added.** Beyond the spec's four gate steps, `run` also asserts
  the action's recipe lives on the requested machine, throwing `ActionNotFoundException`
  (404, existence never leaked) on a mismatch — so `{machineId, actionId}` cannot be
  crossed.
- **After-commit dispatch.** The async task is submitted via a
  `TransactionSynchronization.afterCommit` hook (`submitAfterCommit`), not inline at
  save time, so the worker can never observe a run that has not yet committed. Falls
  back to immediate dispatch when no transaction is active (direct service calls in
  tests). This wasn't in the spec but is required for correctness.
- **Ownership via the entity graph, not `callerUserId`.** `requireRun` scopes with
  `findByIdAndAction_Recipe_Machine_Owner_Id(id, currentUserId)` — ownership derives
  from the run's action → recipe → machine → owner (011) chain. `callerUserId`/`via`
  are recorded on the `Run` for audit (who ran it) but are **not** the access-control
  key; a run is visible to the machine's owner.
- **Persistence is accumulate-then-write, not per-chunk append.** The SSH `OutputSink`
  publishes each chunk live to `RunOutputHub` but accumulates stdout/stderr in
  in-memory `StringBuilder`s and writes them to the `Run` once, in `finish()`, together
  with the exit code and terminal status. The spec's "appended as chunks arrive" onto
  the persisted `Run` was not implemented as incremental DB appends; the DB sees one
  write at completion. A crash mid-run therefore loses that run's output (status stays
  `RUNNING`).
- **MCP progress consumer deferred.** The hub is built to fan out to multiple
  subscribers, but only the SSE subscriber exists today; the MCP progress consumer
  (fan-out target (b)) lands with spec 008.
- **Failure exit code.** A `RuntimeException` from `execStreaming` lands the run as
  `FAILED` with `exitCode = -1`, capturing the exception message as stderr when the
  stream produced none.
- **New exceptions.** `run/service/RunNotFoundException` (404) and
  `run/service/ActionModifiedException` (409) were added; the latter is the run-time
  hash re-check named in the spec.

No `CONTRIBUTING.md` in this repo, so no change-division rule to measure against; the
branch split cleanly into one commit per layer (migration+model, service, REST, tests)
plus the todo→doing marker, which reads sensibly. `## API Modules` in CLAUDE.md is
**None**, so no API Diff subsection.

### Deferred — new hardening spec (RunOutputHub in-memory lifecycle)

Two in-memory `RunOutputHub` concerns are **not** covered by the Known Gaps above
(which are about DB retention/redaction and S7 concurrency) and warrant a fresh
hardening spec:

- **Channels are never evicted.** `channels.computeIfAbsent` creates one `Channel` per
  `runId`, and `complete()` deliberately **retains** it — with its full output history
  — forever, so a late or post-completion subscriber can still replay. This is
  unbounded in-memory growth: every run ever executed keeps its entire stdout/stderr in
  the heap for the life of the process. Needs channel eviction / TTL (e.g. drop N
  seconds after completion, or once the last subscriber drains and a grace window
  elapses).
- **Network I/O under the per-Channel monitor.** `publish()` and `subscribe()` invoke
  `Subscriber.onEvent` — which for the SSE path is `SseEventSink.send`, a network write
  — while holding `synchronized (channel)`. One slow or stalled SSE client therefore
  blocks the SSH streaming thread (and every other subscriber) for that run. The
  hardening spec should move the send outside the lock (snapshot subscribers under the
  lock, dispatch outside) or hand off to a per-subscriber queue.
