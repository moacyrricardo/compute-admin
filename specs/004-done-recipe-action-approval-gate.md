# 004 — Recipe & Action model, approval gate & audit

> **Status:** done — branch `moacyrricardo/spec-004-recipe-action-approval-gate`
> (merged to `main`). Linear is blocked for this repo, so no issue identifier.

## Context

This is the **security core**. It defines recipes, the runnable **action**
(structured argv + typed params + `sudo` + approval state), the REST-only
approval state machine, the content-hash binding that defeats approve-then-mutate
(TOCTOU), and the Envers audit of the approval transition. The run path (005) and
MCP tools (008) build directly on it. Conventions per ARCH.md; audit foundation
(revision entity + listener + `revinfo`) already exists from 003.

## Decision

An action's command is modelled as **structured tokens**, not a template string:
an ordered list of argv tokens (`LITERAL` text or `PARAM` reference) plus a typed
**`ParamDef`** per parameter. This is the injection-safe, human-reviewable shape
chosen for the gate. Approval binds to an **immutable content hash**; any edit to
the command/params/sudo drops the action back to `DRAFT`.

```
Action "restart nginx"  sudo=true
  argTokens: [LIT "systemctl", LIT "restart", PARAM "svc"]
  paramDefs: [ svc: ALLOWED_SET {nginx, docker, mysql} ]
  -> approved argv: ["sudo","-n","systemctl","restart","nginx"]
```

## Implementation

**`recipe/model`.**
- `Recipe` — `@Audited`; `String id`; `@ManyToOne Machine machine`; `name`;
  `String description` (free text, display-only, never executed);
  `RecipeType type` (`NGINX | DOCKER | DATABASE | CRON | CUSTOM`, `@Enumerated
  STRING`); `Instant createdAt`. A recipe belongs to exactly **one** machine;
  cross-machine reuse comes from **blueprints** (spec 010), which instantiate
  per-machine recipes — nullable provenance `sourceBlueprintId` +
  `sourceBlueprintVersion` record where an instantiated recipe came from.
- `Action` — `@Audited`; `String id`; `@ManyToOne Recipe recipe`; `name`;
  `String description` (free text, display-only, never executed — this is what a
  human reads when approving); `boolean sudo`; `ApprovalState approvalState`
  (`DRAFT | PENDING_APPROVAL |
  APPROVED | REVOKED`, default `DRAFT`); `String approvedSnapshotHash` (nullable);
  `Instant approvedAt` + `String approvedByUserId` (nullable — the user who
  approved, always via UI). `@OneToMany` ordered `argTokens`, `@OneToMany
  paramDefs`. Ownership derives through `recipe.machine.owner` (011); every
  recipe/action operation is scoped to the current user.
- `ArgToken` — `String id`; `@ManyToOne Action action`; `int position`;
  `TokenKind kind` (`LITERAL | PARAM`); `String value` (literal text, or the
  param name for `PARAM`). Loaded ordered by `position`.
- `ParamDef` — `String id`; `@ManyToOne Action action`; `String name`;
  `ParamKind kind` (`ALLOWED_SET | REGEX | INT_RANGE`); nullable `pattern`
  (REGEX), `intMin`/`intMax` (INT_RANGE); `@OneToMany` `ParamAllowedValue`
  (ALLOWED_SET). Enum-style params are `ALLOWED_SET`.

**Content hash.** `ActionSnapshot.hash(Action)` — SHA-256 over a canonical
serialization of `(ordered argTokens, paramDefs sorted by name with their rules,
sudo)`. Deterministic; independent of ids/timestamps.

**State machine (`recipe/service/ApprovalService`).**
- `submitForApproval(actionId)`: `DRAFT → PENDING_APPROVAL`.
- `approve(actionId)`: `PENDING_APPROVAL → APPROVED`; stores
  `approvedSnapshotHash = hash(action)`, `approvedAt`, `approvedByUserId =
  CurrentUser.require()`. **REST-only and UI-only** — reached only from
  `ActionRS` (`@Secured`, so `via = UI`); the `mcp` module never references
  `ApprovalService`, and a user may approve only his own actions.
- `revoke(actionId)`: `APPROVED | PENDING_APPROVAL → REVOKED`.
- Any **structural edit** (via `ActionService.editAction`) sets state `DRAFT` and
  clears `approvedSnapshotHash` — so an action cannot be approved benign then
  mutated. Enforced centrally in the edit path, not left to callers.

**`recipe/service`.**
- `RecipeService` — create recipe (validate machine exists, name non-blank).
- `ActionService` — `addAction(AddActionInput)` (build tokens + param defs;
  validate each `ParamDef`: ALLOWED_SET non-empty, REGEX compiles, INT_RANGE
  min≤max; validate every `PARAM` token names a declared def and every def is
  referenced), `editAction(...)` (resets approval as above).
- `ParamBinder` — `List<String> bind(Action, Map<String,String> params)`:
  validate each supplied value against its `ParamDef` (member of allowed set /
  matches anchored regex / integer in range), then walk `argTokens` substituting
  `PARAM` values, prepending `["sudo","-n"]` when `sudo`. Throws
  `ParamValidationException`. Reused verbatim by 005. Values are emitted as
  discrete argv elements — never a shell line (S4).

**`recipe/api`.**
- `RecipeRS` (`@Path("/recipes")`, `@Secured`): create recipe, list actions.
- `ActionRS` (`@Path("/actions")`, `@Secured`): `POST /` add; `PUT /{id}` edit; `POST
  /{id}/submit`; `POST /{id}/approve`; `POST /{id}/revoke`. Returns `RecipeDtos`
  records.
- `RecipeDtos`: `RecipeRequest(machineId, name, description, type)`,
  `AddActionRequest(recipeId, name, description, sudo, List<ArgTokenInput>,
  List<ParamDefInput>)`, `ActionView.of(Action)` (includes `description`,
  `approvalState`, and a `pendingApproval` flag), `RecipeView.of(Recipe)`
  (includes `description` + provenance), `ParamDefView`, `ArgTokenView`.

**Exceptions** (domain in `recipe/service`, mappers in `common`):
`RecipeNotFoundException`/`ActionNotFoundException` (404),
`ParamValidationException` (400), `ActionNotApprovedException` (409),
`IllegalApprovalTransitionException` (409).

**`audit`.** Add `@Audited` on `Recipe`/`Action`; migration `V4__recipe_action.sql`
creates `recipe` (with `source_blueprint_id`/`source_blueprint_version`
provenance columns for 010), `action`, `arg_token`, `param_def`,
`param_allowed_value`, plus `recipe_aud`/`action_aud` (audited columns +
`rev`/`revtype`). Header comment names spec 004.

**Tests.**
- `ApprovalServiceTest` (`@DataJpaTest` slice): full transition matrix; edit of an
  APPROVED action resets to DRAFT and clears the hash; approve records
  `approvedByUserId`; approving another user's action → 404.
- `ParamBinderTest` (unit): ALLOWED_SET/REGEX/INT_RANGE accept+reject; argv
  ordering; sudo prefix; rejection of out-of-set values.
- `ActionSnapshotTest`: hash stable across id/timestamp changes, differs on
  token/param/sudo change.
- `GateArchTest`: asserts **no class in package `…mcp` references
  `ApprovalService`** (the structural guarantee that MCP can't approve).

## Known Gaps

- **S4 — parameter validation is the whole gate.** Discrete-argv binding stops
  shell injection but not **argument injection** (a value bound as argv can still
  act as a flag, e.g. a leading `-`, or a tool-specific `--exec` option).
  `ALLOWED_SET` is the strong shape; `REGEX` must be anchored and is the audited
  escape hatch. A library of vetted safe param types is a follow-up.
- **S5 — `sudo` assumes passwordless sudo**; a per-command sudoers allowlist is
  the eventual hardening.
- Envers records the approval transition + ambient actor, but under S1/S8 the
  actor is unauthenticated — change history, not principal accountability.

## Implementation Notes (done)

Branch `moacyrricardo/spec-004-recipe-action-approval-gate` (PR #8), off `main`.
Implemented per spec; `mvn verify` green (72 tests). Notes:

- **Gate verified end to end** by spec-eval: REST/UI-only state machine, the
  content-hash TOCTOU reset-on-edit, injection-safe `ParamBinder` (validated
  argv), 404 owner-scoping via `recipe.machine.owner`, and `V4` `_aud` tables
  matching spec-003's validity strategy.
- **`GateArchTest`** is a source-file scan of the `mcp` package (no ArchUnit dep
  added) asserting it references neither `ApprovalService` nor a `Repository` —
  the machine-checkable proof MCP can't reach approval.
- H2 reserved-word `value` → columns `token_value` / `allowed_value` (entity
  fields stay `value`); ordered `LinkedHashSet` collections avoid
  `MultipleBagFetchException` under `open-in-view=false`.
- **Deferred to a fresh spec (low priority):** `ActionSnapshot`'s canonical
  serialization uses unescaped `|`/newline delimiters — a theoretical
  hash-collision surface, currently moot; harden only if the approval hash ever
  becomes a cross-principal integrity control.
