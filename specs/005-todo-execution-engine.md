# 005 — Execution engine (async jobs + live streaming)

## Context

With the gate (004) and the SSH adapter (003) in place, this spec runs approved
actions. Runs are **asynchronous jobs** with **live-streamed** output to the UI
(SSE) and to MCP (progress), a captured exit code, and an append-only record. It
is the single shared entry point both the UI and the MCP `run_action` tool (008)
call — so the gate is enforced in exactly one place.

## Decision

- **`Run`** — append-only execution log (not Envers-audited; already immutable):
  `action`, `machine`, `callerActor` (`UI`/`MCP`), `paramsSnapshot`,
  `resolvedCommand` (the exact argv that ran), `approvedSnapshotHash` (the hash
  in force at run time), `status` (`QUEUED | RUNNING | DONE | FAILED`),
  `exitCode`, timestamps.
- **`RunService.run(machineId, actionId, params)`** is the one gate-enforcing
  entry point. In order: (1) assert action is `APPROVED`; (2) assert the action's
  **live content hash equals `approvedSnapshotHash`** (defends the 004 snapshot
  binding at execution time); (3) validate `params` against the action schema;
  (4) bind to argv; (5) hand argv + `sudo` to `SshExecutor`. Any failure → typed
  error, no execution.
- **Streaming:** stdout/stderr stream live over an SSE endpoint for the UI and as
  MCP progress for the agent; both persist to the `Run` record.

## Implementation

- `run/model`: `Run`, `RunStatus`. `run/repository`: `RunRepository`.
- `run/service`: `RunService`; async execution via a bounded task executor.
- `run/api`: `RunRS` — `POST` to start (used by UI), `GET /api/runs/{id}` for
  status, and an SSE endpoint for live output (one of the few places allowed to
  hand-build a streaming `Response`).
- The streaming `SshExecutor` variant from 003 feeds an output sink that fans out
  to SSE subscribers, MCP progress, and the persisted record.
- Migration `V4__run.sql`.
- `resolvedCommand` and `approvedSnapshotHash` are written on the `Run` so the
  log can prove exactly what executed under which approved definition.

## Known Gaps

- **S7 — no rate limiting / concurrency cap.** A misbehaving agent can fan out
  many runs. Also **no per-machine serialization**: two runs mutating the same
  service (e.g. concurrent `restart nginx`) can interleave. Add a per-machine cap
  + global quota when more than one agent can drive it.
- **Command output is stored and streamed unredacted.** Service commands often
  print secrets (env, connection strings, `docker inspect`); the `Run` record and
  the unencrypted H2 DB then hold them. No redaction or retention policy in v1.
- `get_run` output has no access control (paired with S1).
