# 010 — Recipe blueprints (author once, instantiate per-machine)

## Context

Recipes are per-machine (spec 004): a `Recipe` belongs to one `Machine` and every
action is approved per-machine. That is correct for the gate, but it means a
convention shared across the fleet — "every app box has `/opt/app/deploy.sh` with
the same build → migrate → restart steps" — must be recreated by hand on each
machine. This spec adds **blueprints**: a machine-independent recipe definition
you author once and **instantiate** onto many machines (explicitly or by tag).

The invariant is preserved deliberately: a blueprint is **not runnable and has no
approval state**. Instantiation produces ordinary per-machine `Recipe` + `Action`
rows in `PENDING_APPROVAL`, and **each instantiated action is approved
per-machine** through the 004 gate. Authoring is shared; approval is never — one
approval never covers more than one (action, machine).

## Decision

Add a `RecipeBlueprint` + `BlueprintAction` definition (same shape as
`Recipe`/`Action` but with **no machine, no approval state, no run**), and an
`InstantiationService` that copies a blueprint onto target machines as normal
recipes/actions, recording provenance. Blueprint edits bump a version; re-
instantiating reconciles targets, and any instantiated action whose content
changed drops back to `DRAFT` (via the 004 content-hash rule) so it must be
re-approved — a blueprint change can never silently alter an approved action.

## Implementation

**`blueprint/model`.**
- `RecipeBlueprint` — `@Audited`; `String id`; `@ManyToOne AppUser owner` (011,
  blueprints are per-user, never shared); `name`; `String description`;
  `RecipeType type` (reuses 004's enum); `int version` (starts at 1, bumped on
  edit); `Instant createdAt/updatedAt`. No machine, no approval.
- `BlueprintAction` — `String id`; `@ManyToOne RecipeBlueprint blueprint`;
  `name`; `String description`; `boolean sudo`; ordered `argTokens`; `paramDefs`.
  Mirrors the `Action` command shape (LITERAL/PARAM tokens + `ALLOWED_SET / REGEX
  / INT_RANGE` defs) with blueprint-owned child rows (`blueprint_arg_token`,
  `blueprint_param_def`, `blueprint_param_allowed_value`). No approval state.

**`blueprint/service`.**
- `BlueprintService` — `createBlueprint`, `addBlueprintAction`, `editBlueprint`
  (bumps `version`), `list`. Same authoring validation as 004
  (`ActionService`-equivalent checks on tokens/param defs). Absolute-path
  validation applies to `CUSTOM` blueprint actions (per 007) — this is the "shared
  `deploy.sh`" case.
- All `BlueprintService`/`InstantiationService` methods scope to
  `CurrentUser.require()`; a blueprint or machine not owned by the current user is
  a 404. A user may instantiate only onto **his own** machines.
- `InstantiationService.instantiate(blueprintId, Target)` where `Target` is an
  explicit `Set<machineId>` **or** a tag (resolved within the current user's
  machines):
  - For each target machine, find the recipe already instantiated from this
    blueprint (`sourceBlueprintId`), or create one. Copy `name`/`description`/
    `type` and each blueprint action into `Recipe`/`Action` rows with
    `sourceBlueprintId` + `sourceBlueprintVersion` set (004 provenance fields).
  - New/updated actions land `PENDING_APPROVAL`. Reconciliation of an existing
    instance goes through `ActionService.editAction` (004), so an action that was
    `APPROVED` and whose content changed resets to `DRAFT` and must be re-approved.
    Approved actions whose content is unchanged are left approved.
  - Never approves; never runs; only writes recipe/action config.

**`blueprint/api/BlueprintRS`** (`@Path("/blueprints")`, `@Secured`): CRUD
blueprints + `POST /{id}/instantiate` (body `{machineIds?, tag?}`) → the
instantiated recipes per machine (`BlueprintDtos`). Returns DTO records.

**MCP surface (create tools, per the invariant — never approve).** Extends 008:
`add_blueprint`, `add_blueprint_action`, `list_blueprints`,
`instantiate_blueprint(blueprintId, machineIds?|tag?)`. All delegate to
`BlueprintService`/`InstantiationService`; none approve or run.

**Migration `V6__recipe_blueprint.sql`** — `recipe_blueprint` (with `owner_id` →
`app_user`), `blueprint_action`, and the blueprint child tables, plus `_aud`
companions for the audited definition tables. (The 004 `recipe`/`action`
provenance columns `source_blueprint_id`/`source_blueprint_version` are added in
004's migration.)

**Tests.**
- `BlueprintServiceTest` (slice): author a blueprint with several actions +
  descriptions; validation matches 004.
- `InstantiationServiceTest`: instantiate onto 3 machines (and by tag) → 3
  recipes, each action `PENDING_APPROVAL`, provenance set; approve one machine's
  action, re-instantiate an **unchanged** blueprint → that approval survives;
  edit the blueprint (version bump) + re-instantiate → the previously-approved
  action resets to `DRAFT`.
- `BlueprintGateTest`: assert `RecipeBlueprint`/`BlueprintAction` have no approval
  state and no run path, and that the `mcp` blueprint tools never reference
  `ApprovalService` (extends `GateArchTest`).

## Known Gaps

- **No auto-instantiation onto newly-registered machines** matching a tag —
  instantiation is explicit in v1. A "keep tag X in sync with blueprint Y"
  listener/job is a candidate follow-up.
- **Drift is visible, not enforced.** If an instantiated action is edited directly
  on its machine (004), it diverges from the blueprint; provenance shows the
  source but nothing forces re-sync. Re-instantiate to reconcile.
- Blueprint `CUSTOM` actions inherit 007's gap: approval binds to the script
  **path**, not its contents, on each target machine.

## Sequencing

Build after `001`–`008` (needs the 004 action model + content-hash edit path, and
reuses the 006/007 action shapes). Independent of `009`; a strong first
follow-up once the per-machine core works end to end.
