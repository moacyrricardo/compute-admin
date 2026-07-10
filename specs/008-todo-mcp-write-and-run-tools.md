# 008 — MCP write & run tools

## Context

002 proved the MCP seam with one read-only tool. This spec completes the MCP
surface: the create tools (which never approve) and `run_action` (which refuses
anything not `APPROVED`). It is the concrete embodiment of the core invariant —
**registration open over MCP, approval never over MCP, run only when approved.**

## Decision

Add the ARCH.md MCP surface as thin `*Tool` handlers that delegate to the same
feature services the REST layer uses. The `mcp` module holds **no business
rules** and touches **no repositories**.

- **Read:** `list_recipes(machineId)`, `list_actions(machineId, recipeId)`
  (includes actions marked `pending_approval`), `get_run(runId)` — alongside
  `list_machines` from 002.
- **Create (never approves):** `register_machine`, `tag_machine`, `add_recipe`,
  `add_action`, `discover_recipes(machineId)`, `discover_cloud(provider)`.
- **Run:** `run_action(machineId, actionId, params)` → `runId`; delegates to
  `RunService.run` (005), which refuses non-`APPROVED` actions and invalid
  params. Streams output via MCP progress.
- **Resources:** the app's public SSH key (from 003) and run output.

## Implementation

- `mcp/*Tool` beans registered on the MCP `Server` from 002.
- Each tool maps request → service call → DTO response. Errors surface the typed
  domain exceptions (e.g. `ActionNotApprovedException`) as tool errors.
- `list_actions` marks non-approved actions `pending_approval` so an agent can
  ask a human to approve — it cannot approve them itself.

## Known Gaps

- **No approve tool — by design.** This is the load-bearing invariant. Its safety
  depends on the REST approval endpoint being reachable **only** by a real UI
  user and **not** by the same caller that speaks MCP. Under S1/S8 (no auth on
  either surface) that distinction is not technically enforced — an actor that
  can reach `/mcp` can also reach `/api`. A security spec (auth + CSRF/Origin
  checks + loopback bind) is a prerequisite for the invariant to actually hold;
  flagged in 001 and not yet owned by a spec.
- **S8 — MCP transport unauthenticated.** Gate it with the same auth once S1 is
  addressed.
