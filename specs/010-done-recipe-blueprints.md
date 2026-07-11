# 010 — Recipe blueprints (author once, instantiate per-machine)

**Status:** done · **Branch:** `moacyrricardo/spec-010-recipe-blueprints` ·
**Linear:** blocked for this repo — tracked as `spec-010` (no issue identifier).

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

## Implementation Notes

Built as specified — the `blueprint` package (model / repository / service / api),
the four MCP create tools, and `V6__recipe_blueprint.sql`. The invariant holds:
blueprints carry no approval state and no run path, and instantiation only writes
per-machine `Recipe`/`Action` config, never approves and never runs. How the code
diverged from or refined the spec:

- **`instantiate` signature.** The spec's abstract `Target` became a concrete
  `InstantiationService.InstantiateInput(Set<String> machineIds, String tag)`
  record. Supplying **both or neither** is a `400` (`resolveTargets` uses an
  exactly-one check); tag resolution reuses `MachineService.list(tag)`, which is
  already owner-scoped, and explicit ids go through `MachineService.requireMachine`
  (404 on a machine the current user doesn't own) — so ownership is enforced on
  both target paths.
- **New vs. changed actions land in different states** (a refinement of the spec's
  "new/updated actions land PENDING_APPROVAL"). A brand-new instantiated action is
  created then `submitForApproval`-ed to `PENDING_APPROVAL` (a submit, never an
  approve). An **existing** action whose content changed is pushed through
  `ActionService.editAction`, which — via the 004 content-hash rule — resets it to
  `DRAFT` and clears its approval, so a re-instantiation surfaces changes for
  re-submission + re-approval rather than silently re-arming an old approval. This
  matches the Decision section's "drops back to DRAFT" wording.
- **No-op re-instantiation preserves approvals.** Change detection compares a
  transient, never-persisted `desiredHash(blueprintAction)` (an `Action` built from
  the blueprint's tokens/params/sudo, hashed via `ActionSnapshot.hash`) against the
  existing instance's snapshot hash. Equal hash ⇒ the action (and any approval) is
  left completely untouched, so approving on machine A and re-instantiating an
  unchanged blueprint keeps A approved — exactly the `InstantiationServiceTest`
  case.
- **Added `editBlueprintAction` (not in the spec's method list).** The spec listed
  `createBlueprint / addBlueprintAction / editBlueprint / list`; the structural
  edit of an individual blueprint action was needed to actually exercise the
  version-bump → reconcile → DRAFT-reset flow, so `BlueprintService.editBlueprintAction`
  and `PUT /blueprints/actions/{actionId}` were added. It bumps the owning
  blueprint's `version` like `editBlueprint` does, and clears + `saveAndFlush`es the
  old tokens/params before re-inserting so orphan deletes run before inserts and
  cannot collide with the `(action, position)` / `(action, name)` unique
  constraints (an insert-before-delete autoflush hazard). The REST surface is
  correspondingly fuller than the spec's "CRUD + instantiate" sketch: it also
  exposes `GET /{id}`, `GET /{id}/actions`, and `PUT /{id}`.
- **Authoring validation is shared with 004** as specified — every `PARAM` token
  must name a declared def, every declared def must be referenced, param rules are
  well-formed, and a `CUSTOM` blueprint action's leading literal must be an absolute
  path (the shared-`deploy.sh` case, spec 007).
- **Migration scope.** Only `recipe_blueprint` and `blueprint_action` are
  `@Audited` and get hand-written `_aud` companions (Envers validity strategy); the
  structural child tables are `@NotAudited` (their content is captured indirectly by
  an instantiated action's approved snapshot hash). The `recipe` provenance columns
  (`source_blueprint_id` / `source_blueprint_version`) come from V4, as the spec
  noted.

**Tests.** `BlueprintServiceTest` (authoring + validation parity with 004),
`InstantiationServiceTest` (instantiate onto multiple machines and by tag;
provenance set; unchanged re-instantiation preserves an approval; version-bump
re-instantiation resets a changed approved action to DRAFT), and `BlueprintGateTest`
(extends `GateArchTest`: blueprint types have no approval state / no run path, and
the blueprint MCP tools never reference `ApprovalService`).

**Change division.** No `CONTRIBUTING.md` in the repo, so there is no project
authority to assess against. For the record, the branch split cleanly by concern:
model + repositories + V6 schema → services → REST + MCP tools → tests (one commit
each, `spec-010 …` subjects), plus the isolated rename-to-doing commit, matching the
global default (one logical concern per commit; migration travels with its model).

**Deferred to new architecture** — `new-arch: []`. No new entries to ARCH.md's
deferred-risk register: blueprints reuse the existing 004 gate and command model,
are per-user (consistent with S1's resolution in spec 011), and the only security
caveat — `CUSTOM` approval binds to the script path, not its contents on each
target — is the pre-existing spec-007 / S4 gap, restated in Known Gaps, not a new
risk. The feature-level follow-ups (tag auto-sync, drift enforcement) are recorded
under Known Gaps above.
