# 005 — Execution engine (async jobs + live streaming)

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
  `Actor callerActor`; `String paramsJson` (supplied params snapshot);
  `String resolvedArgvJson` (the exact argv executed); `String
  approvedSnapshotHash` (hash in force at run time); `RunStatus status`
  (`QUEUED | RUNNING | DONE | FAILED`); `Integer exitCode` (nullable);
  `@Lob String stdout`, `@Lob String stderr` (appended as chunks arrive);
  `Instant createdAt/startedAt/finishedAt`.

**`run/service/RunService.run(machineId, actionId, params)`** — the gate:
1. `requireMachine` (003) and `requireAction` (004).
2. Assert `action.approvalState == APPROVED`, else `ActionNotApprovedException`.
3. Assert `ActionSnapshot.hash(action) == action.approvedSnapshotHash`, else
   `ActionModifiedException` (defends the 004 binding at run time).
4. `ParamBinder.bind(action, params)` → argv (throws `ParamValidationException`).
5. Persist `Run(QUEUED, callerActor=CurrentActor.require(), resolvedArgvJson,
   approvedSnapshotHash)` (`@Transactional`), return its id.
6. Submit to the async executor; the task sets `RUNNING`, calls
   `SshExecutor.execStreaming` with an `OutputSink`, and on completion sets
   `exitCode` + `DONE`/`FAILED` (its own tx).

**Streaming (`run/service/RunOutputHub`).** Per-run fan-out: the `OutputSink`
pushes stdout/stderr chunks to (a) SSE subscribers, (b) any MCP progress consumer
(008), and (c) the persisted `Run` (append). Late subscribers get the buffered
prefix then the live tail. Backed by the `config/AsyncConfig`
`ThreadPoolTaskExecutor` (bounded core/max/queue).

**`run/api/RunRS`** (`@Path("/runs")`):
- `POST /` — body `{machineId, actionId, params}` → `RunDtos.RunView` (runId +
  status). Used by the UI.
- `GET /{id}` → `RunView` (status, exitCode, timestamps).
- `GET /{id}/output` — `text/event-stream`; hand-built streaming `Response` via
  JAX-RS `Sse`/`SseEventSink` (an allowed exception to "resources return DTOs").
- `RunDtos`: `RunRequest`, `RunView.of(Run)`.

**`run/repository/RunRepository`.** Migration `V4__run.sql` (`run` table, no
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
- `get_run` / the output endpoint have no access control (paired with S1).
