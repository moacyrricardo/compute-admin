# 036 — Recipe & param discovery lifecycle

> **Concern** — options open, not a decision. Raised from three related questions:
> does re-discovery pick up added/removed sites? is there any hide/remove for a
> recipe? and can a fan-out param list (e.g. the flood of systemd units) be curated
> per item? The code today answers "add yes, remove no; revoke only; curate no." This
> concern frames the **lifecycle beyond approval** so a future decision can resolve
> the three together. Grounded in a read of `discovery`/`recipe`/`run` as of spec-033.

## Problem

Discovery and the approval gate handle *creation* well but have **no story for change
over time**. Three gaps, all confirmed in code:

### (a) Re-discovery adds & refreshes, but never retires a vanished resource

`DiscoveryService.discover` reconciles by the identity triple `(machine, type, name)`
(`RecipeService.getOrCreateDiscovered`, `RecipeService.java:94-103`) and per-action by
name (`DiscoveryService.reconcileAction`, `DiscoveryService.java:183-219`):

- **Additions** are picked up: a new site/unit/container → a missing action is added +
  submitted (`CREATED`), or an unapproved proposal is refreshed in place
  (`REFRESHED`, resetting to DRAFT), or an APPROVED action whose definition now differs
  surfaces `DIFFERS_AWAITING_REAPPROVAL` (`:208-215`). That last signal is **transient**
  — it rides out on the `ReconciledActionView` response (`DiscoveryDtos.java:48-53`), it
  is **not persisted** on the Action (there is no `changedSinceApproval` column).
- **Removals are never seen.** `persist()` iterates only over the discoverers' current
  `proposals` (`DiscoveryService.java:150-173`); `reconcileAction` runs only over
  `proposal.actions()`. An action whose underlying resource has **disappeared** (an
  nginx site deleted, a systemd unit removed, a container stopped) is simply never
  visited — nothing marks it stale, retires, or flags it. There is no `lastSeen`/
  `lastDiscovered` field on `Recipe` or `Action`, and no "previously-discovered but no
  longer proposed" pass. So a vanished-resource action **lingers in whatever state it
  was in — including APPROVED and runnable**; running it just fails at SSH time.

### (b) No hide / remove / suppress — `revoke` is the only "stop"

`ApprovalState` is `DRAFT → PENDING_APPROVAL → APPROVED → REVOKED`
(`ApprovalState.java:20-25`), and `REVOKED` is **terminal** (only `DRAFT` can be
submitted, `ApprovalService.java:47-55`, so a revoked action can't be resurrected).
There is **no delete/archive/hide** of a recipe or action anywhere — no delete method
on `RecipeService`/`ActionService`, no `@DELETE` on `RecipeRS`/`ActionRS`/`DiscoveryRS`
(the only `@DELETE`s are `MachineRS`/`TokenRS`; the only repo deletes are run-row
eviction). And there is **no "stop proposing this"** (ignore/suppress/dismiss) for a
discovery proposal: declining to approve does not stop re-discovery from re-proposing
it every run. `revoke` is the sole "don't offer to run this," and only while the
resource still exists (a re-discovered still-present but revoked action stays
`SKIPPED_REVOKED`, `DiscoveryService.java:217`).

### (c) Fan-out lists are all-or-nothing; discoverers flood

- `SystemdDiscoverer` proposes ops over **every running service unit** whose name
  matches `APP_NAME_PATTERN` (`SystemdDiscoverer.java:56-62`) — `--state=running` is the
  only narrowing, so `ssh`, `cron`, `systemd-*` etc. all land in the ALLOWED_SET. Real
  hosts flood.
- `DockerComposeDiscoverer` enumerates **all running containers** (`docker ps`, no
  filter, `DockerComposeDiscoverer.java:205-206`); only the global `ca.discovery.docker.enabled`
  flag throttles it (spec-035 replaces that with per-machine enablement).
- There is **no per-item enable/disable** in a fan-out list. The `APP_PORT_LIST` fan-out
  runs every item in the caller-supplied list (`RunService.runFanOut`, `:207-259`); the
  only way to skip an item is for the caller to pass a subset at run time. No persisted
  per-item flag exists.

### The gate-safety asymmetry that shapes any fix

Two fan-out channels behave **differently** w.r.t. the approval hash, and this decides
where a curation/disable-list can live:

- **`APP_PORT_LIST` pre-fill** is `Recipe.appPortList`, `@NotAudited`
  (`Recipe.java:85-87`), and contributes only its param *kind* to `ActionSnapshot.hash`
  (`ActionService.java:387-388`). Re-writing the list never re-opens approval
  (`RecipeService.refreshDiscoveredAppPortList`, `:113-118`). ⇒ **narrowing this list is
  gate-free.**
- The reserved **`app-name` ALLOWED_SET** *is* hashed — `ActionSnapshot` includes the
  sorted `allowed=` values (`ActionSnapshot.java:70,75-82`). ⇒ changing the target set
  (e.g. a systemd unit set) **forces `DIFFERS_AWAITING_REAPPROVAL`**. A disable-list over
  this channel is either a re-approval event or must be modelled as separate side-data.

**Principle to carry:** *disabling/narrowing the acted-on set is always gate-safe;
widening (adding an un-approved target) is the only thing that must stay gated.*

## Hypotheses / Options (enumerated, not decided)

### A — Vanished resources
- **A1 auto-retire** — when a resource is no longer proposed, revoke/delete its action.
  Clean, but a footgun for APPROVED + audited actions; also fights the "removal is
  explicit" stance from the spec-035 docker-enablement discussion.
- **A2 mark-stale (lean)** — add a `lastDiscovered`/`seenAt` and a reconciliation pass
  that flags "no longer discovered" without deleting; the operator decides. Safe; adds a
  field + a diff pass + a UI state.
- **A3 status quo** — vanished-resource actions linger (incl. runnable APPROVED).

### B — Lifecycle / suppression
- **B1 ignore/suppress list** — "never propose this discovered thing again," persisted
  per `(machine, type, name)` and respected by `reconcileAction` (a new outcome beside
  `SKIPPED_REVOKED`). Needs an un-ignore path.
- **B2 archive/hide states** — extend the lifecycle beyond `REVOKED` (e.g. `ARCHIVED`,
  or a soft-delete flag) with a real delete API.
- **B3 status quo** — decline = re-proposed each run; `revoke` = the only stop.

### C — Fan-out curation (the systemd/docker flood)
- **C1 curate-at-approval** — approve a chosen subset; the approved ALLOWED_SET *is* the
  curated set (works with the hashed-ALLOWED_SET reality — you approve exactly what runs).
- **C2 persistent per-item enable/disable** — a disable set that only ever **narrows**.
  For `APP_PORT_LIST` (un-audited) this is gate-free side-data on the recipe; for the
  hashed `app-name` ALLOWED_SET it must be modelled as separate side-data (not folded
  into the hash) or accepted as a re-approval.
- **C3 discoverer-level scoping** — include/exclude *at* discovery (user units only,
  globs, skip `*.mount`/`*.scope`/system units; container name/label filters) so you
  curate 3 not 300. Cheapest upstream fix; complements C1/C2.
- Likely **C1 + C2 + C3** together.

### D — Discoverer scope defaults
- **D1** per-discoverer config (include/exclude patterns), tying into spec-035's
  per-family enablement (which gates *whether* a discoverer runs; this gates *what* it
  proposes).
- **D2** ship sensible built-in denylists (system systemd units; infra containers).
- **D3** status quo (name-regex + running-state is the only filter).

## Open Questions

- **Auto-delete vs. mark-stale** for vanished resources (A)? And should a stale/orphaned
  APPROVED action be *blocked from running*, or just flagged?
- Does re-discovery need to reconcile fan-out **list membership** (drop items whose
  resource vanished), or only recipe/action identity? Today the list is caller-supplied
  at run time and only *refreshed* by discovery (`RecipeService.java:113-118`) — never
  pruned.
- Where does a disable/ignore set **persist**, given the hash asymmetry — side-data on
  the recipe (gate-free for `APP_PORT_LIST`) vs. re-approval (for the hashed ALLOWED_SET)?
- **Granularity:** per-item (a unit/site/container) vs. per-recipe.
- Should **mutating** recipes (systemd `restart`) treat "add a target" differently from
  read-only MONITOR recipes (where even widening is low-risk)?
- What should each discoverer propose **by default** — is scoping a per-discoverer config
  (D), and does it belong with spec-035's per-family enablement?
- Un-ignore / un-archive / re-surface flow.

## Relationships / not re-covered

Builds on **spec-021** (identity reconcile), **spec-022/026** (`APP_PORT_LIST` fan-out +
`app-name` ALLOWED_SET), **spec-033** (docker discovery + its persistence on the
`app_port_list` column), and the **spec-035** per-family enablement model + the
"disable ≠ delete" stance. It does **not** block the 032–035 monitoring epic; it is a
follow-on. Distinct from the separate **docker-consumer metric poll path** gap (docker
consumers appear from the 032 contract but their axes read `—` until a param-free
docker-check poll is built) — that is a *monitoring rendering* follow-up, not a lifecycle
concern.
