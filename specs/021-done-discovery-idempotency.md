# 021 — Discovery idempotency (reconcile, don't duplicate)

> **Status:** done. **Branch:** `moacyrricardo/monitoring-021-025`. Linear is
> BLOCKED for this repo, so there is no issue identifier — commits use `spec-021`.
> **Prerequisite for the monitoring specs
> (022–026):** monitoring re-runs discovery routinely (a box gains/loses apps),
> and today every re-run *duplicates* the proposed recipes, which would render the
> monitor UI (024) as duplicate app cards. Fix idempotency first.

## Implementation notes

Shipped as specified; the reconciliation state machine and the "surface, don't
mutate" rule for APPROVED-differs landed exactly as decided. Deviations from the
spec text:

- **Migration is `V9`, not `V8`.** `V8` was already taken by spec-018
  (`V8__machine_facts_probed_at.sql`) on `main`; the next free version is
  `V9__recipe_unique_per_machine.sql`. Same content: dedup-then-constrain, keeping
  the earliest recipe per `(machine, type, name)` (tie-broken by smaller id),
  dropping the redundant recipes' action subtrees and any runs recorded against
  those redundant copies, then adding `uq_recipe_machine_type_name`.
- **The `(machine, type, name)` finder already existed.** `RecipeRepository`
  already had `findByMachine_IdAndMachine_Owner_IdAndTypeAndName` (it backed
  `getOrCreateCustom`); `RecipeService.getOrCreateDiscovered` reuses it, so no new
  finder was needed there.
- **`DiscoveryService` stays repository-free.** The per-recipe action lookup is
  exposed as `ActionService.findOnRecipe(recipeId, name)` (owner-scoped via
  `requireRecipe`) wrapping the new `ActionRepository.findByRecipe_IdAndName`, so
  reconciliation never touches a repository directly.
- **Approved-differs hashing** is computed by `ActionService.snapshotHashOf(sudo,
  argTokens, paramDefs)`, which builds a transient `Action` through the same
  `applyStructure` validation the write path uses and hashes it via
  `ActionSnapshot.hash(...)` — no persisted entity, compared against the stored
  `approvedSnapshotHash`.
- **Result shape.** `DiscoveredRecipe.actions()` now carries `ReconciledAction`
  (action + `ReconcileOutcome` + the newly-proposed def for
  `DIFFERS_AWAITING_REAPPROVAL`); the REST DTO gained `ReconciledActionView`
  surfacing the outcome and, for a differing approved action, the proposed
  argv/params.

`mvn -q -B clean verify` is green (173 tests, 0F/0E); `GateArchTest` still passes —
approval remains UI-only.

## Context

Re-running discovery is not idempotent. `DiscoveryService.discover(machineId)`
([`discovery/service/DiscoveryService.java:87`](../src/main/java/com/iskeru/computeadmin/discovery/service/DiscoveryService.java))
loops the proposals and calls `recipeService.create(...)`
([`DiscoveryService.java:101`](../src/main/java/com/iskeru/computeadmin/discovery/service/DiscoveryService.java))
unconditionally for every discoverer on every invocation. `RecipeService.create`
([`recipe/service/RecipeService.java:45`](../src/main/java/com/iskeru/computeadmin/recipe/service/RecipeService.java))
enforces **no per-machine name uniqueness** — it validates only that the name is
non-blank and the machine is owned. So a second `POST /api/machines/{id}/discover`
creates a *second* `docker` recipe, a second `nginx` recipe, and a fresh copy of
every action, each re-submitted to `PENDING_APPROVAL`. This is catalog backlog
item **H2** ([`specs/README.md`](./README.md) deferred-hardening table) and was
called out under spec-006's "Deferred (new architectural concerns)".

For the built-in one-shot discoverers this is annoying; for **monitoring** it is
disqualifying. A monitor dashboard (spec 024) enumerates `MONITOR`-classified
actions and groups them into one card per app; if re-discovery keeps minting
duplicate `springboot monitor` recipes, the dashboard shows N identical `orders`
cards. Monitoring makes re-discovery a routine operation (apps come and go), so
idempotency has to be solved before the monitoring family lands.

## Decision

**Re-running discovery reconciles in place; it never duplicates and never silently
mutates an approval.** The identity of a discovered recipe is the triple
**`(machine, type, name)`** — a discoverer's proposed recipe name is stable per
service type (`"docker"`, `"nginx"`, `"springboot monitor for orders"`, …), so
that triple uniquely names "the recipe this discoverer owns on this machine".

Reconciliation rules, per proposed recipe:

1. **Recipe match by `(machine, type, name)`.** If no such recipe exists, create it
   as today. If one exists, reuse it — never create a second.
2. **Action reconciliation by name within the recipe.** For each proposed action,
   match an existing action by `name`:
   - **No existing action** → add it (`DRAFT`), submit → `PENDING_APPROVAL`
     (exactly today's path).
   - **Existing action in `DRAFT` / `PENDING_APPROVAL` (never approved)** →
     **refresh the proposal in place**: re-apply the proposed argv tokens + param
     defs + `sudo` via the 004 edit path, keep it `PENDING_APPROVAL`. This is what
     lets discovery re-run pick up a changed `ALLOWED_SET` (a new container name,
     a new listening port) before the human has approved.
   - **Existing action `APPROVED` whose proposed definition differs from the
     approved one** → **leave the approval untouched** and **surface a diff**
     (record that discovery re-proposed a different definition). Never auto-edit
     an approved action (that would silently drop it to `DRAFT` via the 004
     content-hash rule) and never create a parallel duplicate.
   - **Existing action `APPROVED` whose proposed definition is identical** →
     no-op (leave it approved).
   - **Existing action `REVOKED`** → leave it; do not resurrect.
3. **Actions no longer proposed** (e.g. a container that disappeared) are **left in
   place** in v1 — discovery adds/updates, it does not delete. Pruning stale
   proposals is Known-Gap forward work (removing an approved action is a
   destructive act that should be an explicit human action, not a discovery
   side-effect).

The **"differs from the approved definition"** comparison reuses the existing
content-hash machinery: build a transient `Action` from the proposal and compare
`ActionSnapshot.hash(...)` against the stored `approvedSnapshotHash` — the very
same technique spec-010's `InstantiationService` uses for no-op detection (see
[`specs/010-done-recipe-blueprints.md`](./010-done-recipe-blueprints.md),
"No-op re-instantiation preserves approvals"). Equal hash ⇒ untouched; different
hash ⇒ record a diff, leave approval alone.

## Implementation

**Uniqueness guard + derived finder (`recipe` module).**
- Add a derived finder on `RecipeRepository`:
  `Optional<Recipe> findByMachine_IdAndMachine_Owner_IdAndTypeAndName(String machineId,
  String ownerId, RecipeType type, String name)` — the owner-scoped `(machine,
  type, name)` lookup. (Note the closely-related
  `findByMachine_IdAndMachine_Owner_IdAndTypeAndName` already backs
  `RecipeService.getOrCreateCustom` at
  [`RecipeService.java:76`](../src/main/java/com/iskeru/computeadmin/recipe/service/RecipeService.java)
  — this generalises the same shape beyond `CUSTOM`, so the finder may already
  exist and just needs reuse.)
- Add a `RecipeService.getOrCreateDiscovered(machineId, type, name, description)`
  helper mirroring `getOrCreateCustom`
  ([`RecipeService.java:68`](../src/main/java/com/iskeru/computeadmin/recipe/service/RecipeService.java)):
  reuse an existing owner-scoped recipe matched by `(machine, type, name)`, else
  `create(...)`. Owner scoping is preserved because the finder joins
  `machine.owner.id = CurrentUser.require().userId()`.
- Add a **named DB uniqueness constraint** `uq_recipe_machine_type_name (machine_id,
  type, name)` in a new migration `V8__recipe_unique_per_machine.sql` (H2 dialect,
  header comment naming spec-021) so the invariant is enforced at the schema level,
  not only in service code. The migration must first **collapse any pre-existing
  duplicates** created by the old non-idempotent path before adding the constraint
  (keep the earliest row per `(machine, type, name)`; re-point or drop the later
  duplicates' actions). Since this repo is single-instance dev data, a
  dedup-then-constrain migration is acceptable; document it in the header comment.

**Reconciliation in `DiscoveryService` (`discovery` module).**
- Replace the unconditional `recipeService.create(...)` at
  [`DiscoveryService.java:101`](../src/main/java/com/iskeru/computeadmin/discovery/service/DiscoveryService.java)
  with `recipeService.getOrCreateDiscovered(...)`.
- Rework the action loop
  ([`DiscoveryService.java:104-110`](../src/main/java/com/iskeru/computeadmin/discovery/service/DiscoveryService.java)):
  for each `ProposedAction`, look up an existing action by name on the reconciled
  recipe (`ActionRepository.findByRecipe_IdAndName(...)`, add if missing) and apply
  the state-machine rules above. Reuse `ActionService.editAction`
  ([`recipe/service/ActionService.java:173`](../src/main/java/com/iskeru/computeadmin/recipe/service/ActionService.java))
  for the DRAFT/PENDING refresh — it already resets approval as a side effect, so
  it is only ever called on **not-yet-approved** actions here (the approved-differs
  branch never calls it). This keeps all state transitions inside the 004 services;
  `DiscoveryService` still never touches a repository directly and still never
  approves.
- The reconciliation runs inside the existing single short `tx.execute(...)` persist
  phase ([`DiscoveryService.java:95`](../src/main/java/com/iskeru/computeadmin/discovery/service/DiscoveryService.java)),
  so spec-013's "probe outside the tx, persist in one short tx" scoping is
  preserved — reconciliation is persist-phase work.

**Diff surfacing.** Extend the `DiscoveredRecipe` result record
([`DiscoveryService.java:44`](../src/main/java/com/iskeru/computeadmin/discovery/service/DiscoveryService.java))
so each returned action carries a small **reconciliation outcome** enum
(`CREATED | REFRESHED | UNCHANGED | DIFFERS_AWAITING_REAPPROVAL | SKIPPED_REVOKED`)
and, for `DIFFERS_AWAITING_REAPPROVAL`, the newly-proposed argv/params so the UI
(and the `POST /api/machines/{id}/discover` response `DiscoveryDtos`) can show the
operator "discovery would change this approved action — review and re-approve to
adopt." No new persisted entity; the outcome is computed per discover call.

**Tests.**
- `DiscoveryServiceTest` (extend): discover twice over the same fake `SshExecutor`
  output → assert **exactly one** recipe per `(machine, type, name)` and no
  duplicate actions (the direct H2 regression). Discover, approve one action, then
  discover again with **identical** probe output → the approval **survives**
  (`UNCHANGED`). Discover, approve, then discover again with **changed** probe
  output (e.g. a new container in the `ALLOWED_SET`) → the approved action stays
  `APPROVED` and is reported `DIFFERS_AWAITING_REAPPROVAL`; a **DRAFT/PENDING**
  action with changed output is `REFRESHED` in place.
- A repository/slice test asserting the `uq_recipe_machine_type_name` constraint
  rejects a duplicate insert.

## Known Gaps

- **No pruning of vanished proposals.** If a container/app disappears, its proposed
  (and possibly approved) action lingers. Deleting an approved action is
  destructive and deserves an explicit human action, not a discovery side-effect —
  a "reap orphaned discovered actions" flow is forward work.
- **Name stability is the identity contract.** Reconciliation keys on the proposed
  recipe/action *name*; if a discoverer changes how it names things between
  versions, the old rows won't match and will look orphaned (see previous gap).
  Discoverer names must therefore be treated as a stable, source-controlled
  contract.
- **Attacker-influenced diff (S3).** A spoofed/compromised target (no host-key
  verification, S3) can push a changed `ALLOWED_SET` on re-discovery; the
  `DIFFERS_AWAITING_REAPPROVAL` outcome is deliberately **non-destructive** — it
  never auto-adopts — so the 004 human re-approval remains the mitigation, exactly
  as in spec-006's Known Gaps.

## Sequencing

**Build first** — it is the prerequisite for 022–026. Touches `recipe`
(finder/helper + migration) and `discovery` (reconciliation). Independent of the
monitoring model; resolves catalog backlog **H2**. On merge, strike H2 through in
[`specs/README.md`](./README.md)'s deferred-hardening table with a pointer here.
