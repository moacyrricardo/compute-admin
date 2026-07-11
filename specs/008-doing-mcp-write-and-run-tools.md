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

**Bootstrap tools (unauthenticated session only):** `begin_setup()` /
`complete_setup(deviceCode)` — the device-authorization pairing from 011. They
delegate to `PairingService`, expose no user data, and are the *only* tools a
tokenless MCP session can call. A human completes the pairing (Google auth +
approve) in the UI; the client then reconnects with the minted personal token.

**Resources:** the app's public SSH key (from 003 `KeyService`) and run output.

Each tool maps request → feature-service call → DTO; no repository access. The
MCP session is authenticated by the caller's **personal token** (011): the
`McpTokenAuthFilter` binds `AuthContext(userId, …, MCP)`, so every tool
automatically scopes to that user's own machines/recipes/runs via the same
owner-scoping services the REST layer uses. A tokenless session can reach only the
bootstrap tools below; all data/run tools are rejected (resolves S8).

**Tests.**
- `McpToolsWebTest` (`@SpringBootTest RANDOM_PORT`): register→add recipe→add
  action over MCP; `list_actions` shows `pending_approval`; `run_action` on the
  unapproved action is refused; approve over **REST**; then `run_action`
  succeeds. Proves the create-open / approve-REST-only / run-when-approved flow.
- `GateArchTest` (extends the 004 check): asserts the `…mcp` package references no
  `*Repository` and no `ApprovalService` — the machine-checkable guarantee that
  the gate can't be bypassed through MCP.

## Known Gaps

- **No approve tool — by design, and now genuinely enforced.** With users (011),
  approval requires a UI-authenticated session (`@Secured`, `via = UI`); the MCP
  personal token binds `via = MCP` and there is no approve tool, so the MCP caller
  has no path to approval. This is enforced two ways: **structurally**
  (`GateArchTest`: `mcp` references neither a repository nor `ApprovalService`)
  and by **auth** (the approve endpoint rejects a non-UI caller). The earlier
  S1/S8 objection (any local caller can hit `/api/.../approve`) is closed by 011.
- **Still local (project decision).** Transport hardening beyond token auth
  (loopback bind, TLS, rate limiting) remains a tracked risk — see ARCH.md.
