# 008 — MCP write & run tools

## Context

002 proved the MCP seam with one read-only tool. This spec completes the MCP
surface: the create tools (which never approve) and `run_action` (which refuses
anything not `APPROVED`). It is the concrete embodiment of the core invariant —
**registration open over MCP, approval never over MCP, run only when approved** —
and the structural guarantee behind it. `discover_cloud` is **omitted** here; it
arrives with cloud import (009, fast-follow).

## Decision

Add the ARCH.md MCP surface (minus cloud) as thin `*Tool` beans that delegate to
the **same feature services** the REST layer uses. The `mcp` module holds no
business rules, touches no repository, and — critically — never references
`ApprovalService`.

## Implementation

**Read tools** (alongside `list_machines` from 002): `ListRecipesTool`
(`list_recipes(machineId)`), `ListActionsTool` (`list_actions(machineId,
recipeId)` — marks non-`APPROVED` actions `pending_approval`), `GetRunTool`
(`get_run(runId)`).

**Create tools (never approve):** `RegisterMachineTool`, `TagMachineTool`,
`AddRecipeTool`, `AddActionTool` (structured argv + param defs per 004),
`DiscoverRecipesTool` (`discover_recipes(machineId)` → proposals in
`PENDING_APPROVAL`).

**Run tool:** `RunActionTool` (`run_action(machineId, actionId, params)`) →
delegates to `RunService.run` (005). Refuses non-`APPROVED`/mutated/invalid via
the typed exceptions surfaced as MCP tool errors. Streams output via **MCP
progress** by subscribing to the run's `RunOutputHub` (005); returns `runId`.

**Resources:** the app's public SSH key (from 003 `KeyService`) and run output.

Each tool maps request → feature-service call → DTO; no repository access.

**Tests.**
- `McpToolsWebTest` (`@SpringBootTest RANDOM_PORT`): register→add recipe→add
  action over MCP; `list_actions` shows `pending_approval`; `run_action` on the
  unapproved action is refused; approve over **REST**; then `run_action`
  succeeds. Proves the create-open / approve-REST-only / run-when-approved flow.
- `GateArchTest` (extends the 004 check): asserts the `…mcp` package references no
  `*Repository` and no `ApprovalService` — the machine-checkable guarantee that
  the gate can't be bypassed through MCP.

## Known Gaps

- **No approve tool — by design.** Its safety rests on the REST approval endpoint
  being reachable only by a real UI user, not by the MCP caller. Under S1/S8 (no
  auth on either surface) that distinction is **not** technically enforced — an
  actor that reaches `/mcp` can also reach `/api`. Per project decision this is
  accepted: the invariant is enforced **structurally** (no code path from MCP to
  approval, guarded by `GateArchTest`), not by auth. A security spec (auth +
  CSRF/Origin checks + loopback bind) remains the prerequisite for the invariant
  to hold against a hostile local caller; not scheduled yet.
- **S8 — MCP transport unauthenticated.** Gate it with the same auth once S1 is
  addressed.
