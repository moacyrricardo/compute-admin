# 053 — App model & verb contract

**Status:** concern (most axes decided in the 2026-08-12 reviews; a short residue stays
open) · no branch · no Linear (blocked for this repo).

> Carries forward the **"what is an app"** half of concern **040**. 040's Open Question 6
> (*does adopting C unwind 032/033/038*) is restated as axis C below. **040's Open
> Question 1 — where read-time shaping runs, i.e. whether `MonitorService`'s host/app
> partition + rollup is on the allowed side of the BFF line — stays with 040.** It is a
> question about the *read path*, not about what an app is, and it is recorded here only
> so neither document assumes the other carries it.

## Problem

compute-admin's answer to **what an app is** is a loose string and nothing more.

1. **An app is a name.** spec-026 introduced a reserved scalar `app-name` param
   (`ALLOWED_SET` — a single value is just a set of size one) whose job is correlation:
   `ParamBinder.targetApps` reads the definition, and `MonitorService.appOps` +
   `opsForApp` are a group-by over that string.
2. **A taxonomy sits beside it.** spec-032's `ConsumerRole` / `ConsumerSource` /
   `Dedication` / `Bucket`, spec-033's compose-project grouping, spec-038's
   dedicated-vs-shared datastores — classified server-side at discovery, then re-joined
   and normalised client-side at every poll. 040 named this: *"what is an app is derived
   twice."*
3. **Detection routes disagree about what counts.** The monitor chain is port-driven
   (`ss -ltnp` → PID → cmdline in spec-025, extended to folders in 049, to lifecycle
   scripts in 050). `SystemdDiscoverer` (026) is not — it enumerates running units. The
   docker path (033) is not either — *"No port is required — portless workers and
   datastores are monitored because they belong to a project."*

These do meet, deliberately: 033 makes the compose project name *be* the `appName`, and
022 unifies double-detected apps *"under one app card by `appName` string match"*. But
they meet **only on that string**, with no verb, probe or MCP semantics attached — and 022
itself flagged the correlation as loose, needing normalisation and, later, an alias map.

Four consequences, each already biting:

- **The verb an action speaks is inferred from its name.** spec-050 renders card controls
  for actions *"named exactly `start`/`stop`/`restart`/`deploy`"*, matched client-side. The
  red-team of 050 found the first structural crack: with a folder `start.sh` + `run.sh` and
  an ancestry launcher `run.sh`, the launcher loses the `start` verb and nothing names it —
  its natural basename is already taken, while the spec asserts *"action names are unique
  within the recipe by construction"* and supplies no construction.
- **One population is genuinely undetectable.** Not "portless" — 026 and 033 both find
  portless work. The residue is **hand-launched and cron-scheduled native, non-systemd**
  workloads: a `run.sh` started by hand, a cron-scheduled batch job, a bare bash worker.
  No discoverer reaches them.
- **Classification is not decoration — it picks the probe.** You cannot measure a postgres
  the way you measure a jar, and "disk usage" means something different for each. Today
  classification's output is *persisted enums*; what a probe actually needs from it is
  *which command to propose*.
- **The MCP surface can only enumerate action ids.** An agent cannot ask what an app
  answers to. It *can* already register an action carrying an `app-name` (008's
  `AddRecipeTool`/`AddActionTool`; 050 records the demo harness doing exactly that), but
  there is no notion of declaring an app, and no verb vocabulary to speak.

## Decisions taken (reviews of 2026-08-12)

Seventeen calls, in the order they were made. The residue that is still open is below.

### The model

1. **A compose project is one app; commands may target a part.** `app-name` stays a
   **flat** namespace (`shop`); a command may additionally declare the **part** it acts on
   (`ui`, `worker`, `db`). The card carries app-level controls *and* per-part controls. No
   app-inside-an-app. A project's own dedicated postgres is simply another part.
2. **Classification survives as a small declared label, stamped once.** Discovery
   classifies in order to choose the right probe, and stamps a short `role` / `dedication`
   label onto the proposed action. Declared at proposal, **never recomputed at read time**.
3. **The verb vocabulary is closed.** `start · stop · restart · deploy · status · logs ·
   metrics` get real controls and are what MCP speaks. Anything else (`reindex`,
   `migrate`, `backup`) is still registered and runnable — it renders as an ordinary run
   chip, not a card button.
4. **A declared app is first-class — for a narrow, named population.** An app may exist
   because a human or an agent declared it, with no detected process behind it. The gap
   this fills is **hand-launched and cron-scheduled native, non-systemd work only**;
   systemd units and compose members are already discovered without any port, so a
   declaration must not become a second way to describe them. Two riders:
   - **v1: a declared app is not monitored.** It is a thing you can run approved commands
     against, not a thing the monitor measures. Nothing on the 049/050 path can measure it
     — 049's probe fans out over `APP_PORT_LIST` with a port as its only param, and 050's
     scan takes PID + `appRoot` and reads `managedBy` from the cgroup.
   - **A reconciliation rule is required** for the case where a declared app and a
     discovered app describe the same workload (see Open Question 2).

### Where declarations live, and what the gate sees

5. **Split by risk (axis A).** **Identity (`app-name`, `part`) and `verb` ride as reserved
   params (A1)**; **`role` and `dedication` ride as un-audited side-data (A4)** — the
   `app_port_list`-JSON shape 033 already uses and 036 proposes. Each declaration sits
   where its risk belongs: a verb decides what a button *does*, `role` is presentation.
6. **A1 carries a new invariant, not an inherited one.** *A declaration param is never
   referenced by an argv token*, enforced where actions are created. This is **new work**:
   it is emphatically not a property of the 026 precedent, whose own actions bind
   `{app-name}` straight into `systemctl status`, `systemctl restart` (under `sudo -n`) and
   `journalctl -u`. 026's own gate-safety argument is different — every bound value is
   validated against `ParamBinder.APP_NAME_PATTERN`, plus `ALLOWED_SET` membership.
7. **`verb` enters the approval hash (axis B).** Identity and verb are inside the snapshot;
   changing a verb requires re-approval. `role`/`dedication` sit outside it by construction
   (decision 5), so reclassification never interrupts an operator.
8. **Declaring an app is a gated act.** A declaration is a **proposal**: it lands
   `PENDING_APPROVAL` and a human approves it in the UI, like every other agent-caused
   effect. The invariant 052 states as non-negotiable holds here unchanged — *the agent
   proposes; only the UI decides*.
9. **A declared app is machine-scoped in v1.** Its commands have to run somewhere, and
   every existing grouping (`appOps`, the monitor) is per machine.
10. **Declared apps never auto-retire.** The declaration **is** the evidence, so "not seen"
    cannot imply "stale" — the UI marks it stale and nothing more. Retirement and
    suppression stay with concern **036**, which owns the vanished-resource problem for
    detected resources too.
11. **Multi-app commands stay first-class.** In 026 a size-one `ALLOWED_SET` is the
    degenerate case, not the contract; multi-app ops actions are the design intent (and
    036's flood problem exists because the set gets large). The app view groups a shared
    command under each app it targets.

### Identity, naming and the MCP surface

12. **Path-derived names are allowed, following 028's precedent.** Only a **basename** may
    seed a name, and it becomes identity only once a human or agent **accepts it at
    approval**; the raw path never reaches MCP. 028 already ruled this shape acceptable for
    machines (`name = host` seeded by migration) on the grounds that the value is *"a name
    the owner chose to accept, not raw infra echoed as coordinates"*. 049's ban is on the
    **paths themselves** (`appRoot`/`artifact` as infra topology), which stands.
13. **022's name-normalisation and alias-map forward work moves here.** 053 now owns app
    identity, so the normalisation rules and the planned alias map (container `orders-api`
    ↔ health-probe `orders`) are this document's to carry.
14. **MCP speaks verbs (F1).** Verb-level tools; the F1/F2 option is retired, and with it
    the contradiction where decision 3 asserted the vocabulary "is what MCP speaks" while
    axis F kept the tool shape open.
15. **`part` is not addressable from MCP in v1.** Part-level control is UI-only; MCP
    addresses the app. "Restart the worker" can arrive once parts have proven themselves.

### Delivery

16. **One probe constant with an internal detector table (axis D).** The shape 049 already
    chose for its runtime detector — *"adding a runtime = adding a table row, not touching
    the schema or the UI contract"* — rather than one fixed constant per role. S4 is
    satisfied either way; this keeps the constant list from multiplying with the taxonomy,
    at the cost of a longer script for a human to read at approval.
17. **Sequencing.** **049 proceeds now, independently** — its blockers are internal and it
    has no coupling to this model. **050 waited only on decision 5** (where declarations
    live) and can now draft against it. Both fix their own red-team findings regardless;
    see *Impact* below.

## The model these imply

```
app          a name, scoped to a machine. Not a first-class entity today —
             022 declined one and left promotion open as "later work"; where
             a declared app with no commands persists is Open Question 3.
contract     the set of verbs its APPROVED commands declare. Derived, and
             PARTIAL BY DESIGN — most real apps answer `start` and nothing
             else. A capability set, never an interface something must
             implement.
command      a verb implementation. Declares app-name + part + verb as
             reserved params (hashed), and role + dedication as side-data
             (not hashed). May target several apps.
classify     a proposal-time input whose OUTPUT IS COMMANDS. Classify once,
             deeply, at discovery; emit the right probe; never classify again.
reading      declares its own unit and whether it is attributable — a shared
             global's number cannot be split per project, which is spec-032's
             decision of record (SHARED = "a real footprint but no per-app
             split"), echoed in 034.
```

The taxonomy the reviews converged on is close to what 032/033/038 already encode, which
is good evidence the model is right; the question was never whether it is correct, but
**where it lives and when it runs**. It separates into two orthogonal axes plus a grouping:

| Axis | What it decides |
|---|---|
| **Packaging** — native (jar / python / bash) · docker single · compose project | how you *measure* (`/proc` vs `docker stats`) and how you *control* (script/systemd vs docker) |
| **Role** — workload · global service (mysql, mariadb, postgres, nginx) | what a useful metric even *is*, and whether the number is attributable |
| **Dedication** — shared · single-dependent | whether one project may claim the resource |
| **Grouping** — compose project | an app that *is* several parts (ui, backend, worker, its own db) |

## Hypotheses / Options

Every axis below is **resolved**; the rejected options stay for the record.

### A — Where the declarations live · **resolved: split A1 + A4**

- **A1 · Reserved correlation-only params** — *chosen for identity + verb.* Proven
  machinery (026), no migration, and 049's *"no new DTO fields"* constraint survives.
  **Its advantage is not inherited**: see decision 6 — "never referenced by an argv token"
  is a new invariant this work must enforce, not a property of the precedent.
- **A2 · Real columns on `Action`** — *rejected.* Most honest modelling, but a migration
  and DTO surface, head-on with 049's *"No backend model change"*, and it edges toward the
  persisted model 040 objects to.
- **A3 · Naming convention only** — *rejected.* Zero mechanism, and it is what produced
  050's name-collision crack.
- **A4 · Un-audited side-data on the recipe** — *chosen for role + dedication.* The
  `app_port_list` JSON shape 033 already uses (`{"dockerConsumers":[…]}`) and 036 proposes
  for exactly this purpose: *"it must be modelled as separate side-data (not folded into
  the hash)"*. Gate-free by construction, which is what makes it right for presentation
  and wrong for a verb.

### B — Do declarations enter the approval hash · **resolved: split**

036 records the asymmetry: narrowing `APP_PORT_LIST` is gate-free, but the `app-name`
`ALLOWED_SET` **is hashed** (`ActionSnapshot` includes the sorted `allowed=` values).

- **B1 · Hash everything** — rejected: reclassifying a `role` would interrupt an operator.
- **B2 · Hash nothing** — rejected: a verb changing what a button *does* without passing
  the gate is a hole, and no argument was found that it is not.
- **B3 · Split** — **chosen**, with the residue the option originally left silent now
  fixed: identity (`app-name`, `part`) **and `verb`** are hashed; `role`/`dedication` are
  not.

### C — What happens to 032 / 033 / 038 · **resolved: C2 coexist for v1**

- **C1 · Convert now** — rejected for v1: it answers 040 properly but touches three `done`
  specs and widens the first delivery considerably.
- **C2 · Coexist** — **chosen**. Declarations are added; the enums stay authoritative
  during the transition. The cost is explicit: **040's double-derivation survives for as
  long as the transition does.** The **end-state** is that the persisted enums become
  declarations stamped at proposal and 032/033/038 are amended or superseded per the
  catalog convention; conversion is tracked as follow-on work, not left implied.

### D — Probe selection under S4 · **resolved: D2**

S4 forbids variable command bodies, so each probe is a fixed source-controlled constant.
**D2 (one constant with an internal detector table)** was chosen over **D1 (one constant
per role)** for consistency with 049's runtime detector.

### E — Retiring a declared app · **resolved: never auto-retire**

036 supplies the shapes for detected resources (auto-retire / mark-stale / linger).
For declared apps the answer is **mark stale in the UI, never auto-retire** — the
declaration is the evidence, so absence of a process is not absence of an app. The general
retire/suppress problem stays with 036.

### F — The MCP surface · **resolved: F1**

Verb-level tools. Retired.

## Open Questions

1. **App-name identity rules.** What is an app name's scope (machine-scoped, per decision
   9 — but is it owner-scoped above that, as 028's machine names are)? What happens when a
   **declared** `shop` meets a **detected** `shop`? And what are the normalisation rules
   and alias map inherited from 022 (decision 13)?
2. **The declared-vs-discovered reconciliation rule** (decision 4's rider). When a
   declaration and a discoverer describe the same workload, which wins, and does the
   declaration retire itself or merge?
3. **Where a declared app persists.** Decision 8 makes a declaration an approvable
   proposal and decision 9 scopes it to a machine — but what is the substrate? Does a
   declaration exist as its own approvable object, or only implicitly as the first command
   that carries the name? 022 declined a first-class `App` entity and left promotion open
   as *"later work if loose correlation proves insufficient"*; this is the question that
   decides whether that later has arrived.
4. **A vocabulary amendment criterion.** Decision 3 closed the verb set by assertion. What
   test does the next candidate (`migrate`, `backup`, `rollback`, `seed`) have to pass —
   e.g. "a verb is added only when it needs a *card control* and MCP semantics, not merely
   a guard"? Without a criterion the closure dies by first-request accretion.
5. **Is "destructive" a declaration orthogonal to the verb?** 050 keys its confirm-drawer
   guard to the verb names, so a command outside the vocabulary — a `migrate.sh` proposed
   under its basename — renders as a plain one-click chip: *the most dangerous script gets
   the least friction*. If a destructive flag is introduced, decision 7's logic applies to
   it too (it changes what confirmation a run demands, so it belongs inside the hash).

## Impact on 049 and 050

Both were red-teamed on 2026-08-12 — 4 blockers, 6 risks and 5 minors across the two, and
those findings were then re-classified against this concern (see `053-assets/`).

**This concern closes none of them.** Of the 15 findings, 0 are dissolved by the model
here, 2 are answered (the meta-risk of building on 040's open leaning, and the shape of
050's name-uniqueness answer), 12 are untouched, and **1 is made worse**: the
symlink-vs-resolved-path blocker gains two further consumers of the same undecided token
(path-derived app-name stability, and S9 sanitisation) while this document supplies no
decision about it. An earlier draft claimed the declared verb *"dissolves"* 050's
name-collision gap; that was an overclaim. It demotes the collision from verb-breaking to
cosmetic — **a uniqueness construction still has to be written**, since 050 asserts one
"by construction" and supplies none.

What this concern does change for each:

- **050** — the verb moves from an inferred action *name* to a declared field. This is the
  load-bearing fix for its fragility, and it also makes affixed script names
  (`start-api.sh`, `prod-deploy.sh`, `01-migrate.sh`) tractable: under verb-as-name,
  promotion is a *rename*, and a rename is a remove+add to spec-021's reconciler, so
  approvals do not carry over; under a declared verb, names are path-derived and stable.
  Two smaller misalignments to settle with it: this vocabulary has seven verbs where 050
  renders four, and 026's existing action is named `tail-logs` where this vocabulary says
  `logs`.
- **049** — largely unaffected. Its proposed `footprint` action would carry a `role`
  declaration alongside the `app-name` it already has; under decision 5 that is side-data
  plus a param definition, so its *"No backend model change. No Flyway migration, no new
  DTO fields"* constraint survives. Its NDJSON schema is the **probe's output** — a
  reading — and is not touched by declarations, which ride on the proposed action.

## Related

- **040** — the concern this carries forward. Its Open Question 6 is axis C above; **its
  Open Question 1 (read-time shaping / the BFF line) stays with 040**, explicitly, so it is
  not orphaned between the two documents.
- **022** — declined a first-class `App` JPA entity, promotion *"explicitly later work"*;
  source of the normalisation + alias-map work decision 13 moves here.
- **026** — the reserved `app-name` param and the `systemctl`-templated actions that
  disprove the "never reaches argv" reading; also the systemd discovery route that needs no
  port.
- **025 / 049 / 050** — the port-driven chain and the residue it cannot reach.
- **028** — the machine-name precedent decision 12 follows (`name = host`, accepted by the
  owner, not raw infra on the MCP surface).
- **032** — the decision of record that a SHARED resource has *"a real footprint but no
  per-app split"*; **033** (compose project = appName, portless containers), **034** (the
  categorical consumer palette), **038** (dedicated vs shared), **041** (`OTHER =
  host_used − Σ attributed`).
- **036** — the `app-name` `ALLOWED_SET` hashing asymmetry axis B turns on, the side-data
  shape A4 adopts, and the retire/suppress problem decision 10 leaves in place.
- **007 / 015** — the command shape and the content-pinning that keys on
  `RecipeType.CUSTOM`.
- **008 / 052** — the MCP tool surface, and the *"the agent proposes; only the UI decides"*
  invariant decision 8 preserves.
- **ARCH.md** — S4 (fixed script bodies, typed params, no free-form commands), S5
  (login-user coverage), S9 (MCP host/path hiding).
