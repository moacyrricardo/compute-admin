# 004 — Recipe & Action model, approval gate & audit

## Context

This is the **security core** of compute-admin. It defines recipes, the runnable
**action** (command template + typed param schema + `sudo` flag + approval
state), the approval state machine, and the Envers audit that records the
approval transition. Every enforcement point in ARCH.md's "the gate" section
lands here. The run path (005) and MCP write tools (008) build directly on it.

## Decision

- **`Recipe`**: `name`, `type` (`NGINX | DOCKER | DATABASE | CRON | CUSTOM`),
  belongs to a `Machine`. `Recipe (1)─(N) Action`.
- **`Action`** — the runnable unit:
  - `commandTemplate` — a template built from a **fixed command** plus **named,
    typed params** bound as **argv elements** (never shell-concatenated).
  - `paramSchema` — each param is `enum` / `allowed-set` / `regex`-validated,
    typed, and named. Enum/allowed-set is the default shape; regex is the
    audited escape hatch.
  - `sudo` flag (per action).
  - `approvalState`: `DRAFT → PENDING_APPROVAL → APPROVED`, plus `REVOKED`. Only
    `APPROVED` is runnable.
- **Approval is REST-only.** The approve transition lives in a `recipe` service
  method exposed through `RecipeRS`/an approval endpoint. The `mcp` module never
  calls it. **There is no approve tool.**
- **Approval binds to an immutable snapshot.** On approve, we compute and store a
  hash of `(commandTemplate + paramSchema + sudo)`. Any edit to those fields
  **invalidates approval** (transitions the action back to `DRAFT`), so an action
  cannot be approved benign and then mutated into something else while retaining
  `APPROVED`. The run path (005) re-checks the live hash against the approved
  hash before executing.
- **Audit.** Hibernate Envers with the validity strategy (`REV`/`REVEND`) audits
  `Machine`, `Recipe`, `Action` — especially the approval transition. A custom
  `AuditRevision` entity + `CurrentActorRevisionListener` stamp the acting actor
  (from `CurrentActor`, 002).

## Implementation

- `recipe/model`: `Recipe`, `Action`, `ApprovalState` enum, `approvedSnapshotHash`
  column on `Action`.
- `recipe/service`: `ApprovalService` (`submitForApproval`, `approve`, `revoke`),
  `RecipeService`/`ActionService` (create, edit). Edit paths recompute the
  content hash and reset to `DRAFT` when it diverges from `approvedSnapshotHash`.
- `recipe/api`: `RecipeRS`, `ActionRS` — the **only** path to `approve`.
- Param validation component: given `paramSchema` + supplied values, validate and
  produce the ordered argv list. Reused verbatim by 005.
- `audit`: `AuditRevision`, `CurrentActorRevisionListener`, Envers config.
- Migration `V3__recipe_action.sql` + Envers audit tables.
- Domain exceptions + mappers: `ActionNotApprovedException`,
  `ParamValidationException`.

## Known Gaps

- **S4 — parameter validation is the whole gate.** Argv binding prevents shell
  injection but not **argument injection** (a param bound as argv can still act
  as a flag, e.g. a leading `-`, or tool-specific `--exec`-style options). Each
  action author is responsible for a tight schema; hand-rolled regex is
  error-prone. Consider a library of vetted safe param types as a follow-up.
- **S5 — `sudo` assumes passwordless sudo** on the target; a per-command sudoers
  allowlist is the eventual hardening.
- Envers records *what changed* and the ambient actor, but under S1/S8 the actor
  is unauthenticated, so the trail is change-history, not principal
  accountability.
