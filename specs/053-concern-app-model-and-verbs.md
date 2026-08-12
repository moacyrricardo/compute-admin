# 053 — App model & verb contract

**Status:** concern (exploratory; four axes decided in the 2026-08-12 review, the rest
open) · no branch · no Linear (blocked for this repo).

> Absorbs the live half of **concern 040** — its Open Question 1 (*where exactly is the
> BFF line*) and Open Question 6 (*does this unwind 032/033/038*) are restated here in
> concrete terms. 040 stays the record of the runs-are-invisible problem and the
> options that framed it; this concern carries "what is an app" forward.

## Problem

compute-admin has no answer to **what an app is** — it has three partial answers that
never meet.

1. **An app is a string.** spec-026 introduced a reserved scalar `app-name` param
   (`ALLOWED_SET` of size one) whose only job is correlation: no `PARAM` argv token
   references it, `ParamBinder.bind` demands values only for tokens, and `targetApps`
   reads the definition. `MonitorService.appOps` + `opsForApp` are a group-by over that
   string. This is the closest thing to an app model, and it is a naming convention.
2. **An app is a taxonomy.** spec-032's `ConsumerRole` / `ConsumerSource` /
   `Dedication` / `Bucket`, spec-033's compose-project grouping, spec-038's
   dedicated-vs-shared datastores — classified server-side at discovery, then re-joined
   and normalised client-side at every poll. 040 named this: *"what is an app is derived
   twice."*
3. **An app is whatever answers on a port.** The detection chain is port-driven end to
   end (`ss -ltnp` → PID → cmdline in spec-025, extended to folders in 049, to lifecycle
   scripts in 050).

Four consequences, each of which is already biting:

- **The verb an action speaks is inferred from its name.** spec-050 renders card
  controls for actions "named exactly `start`/`stop`/`restart`/`deploy`", matched
  client-side. The red-team of 050 found the first structural crack: with a folder
  `start.sh` + `run.sh` and an ancestry launcher `run.sh`, the launcher loses the `start`
  verb and nothing names it — its natural basename is already taken, while the spec
  asserts "action names are unique within the recipe by construction."
- **Portless native workloads are structurally invisible.** A cron script, a queue
  worker, a batch job, a bash script per se — nothing listens, so nothing detects it.
  040 admits docker labels are the only reliable way to see portless *containers* the
  host `ss → PID → /proc` chain "structurally misses"; for native portless work there is
  no equivalent at all, and 049/050 inherit the blind spot.
- **Classification is not decoration — it picks the probe.** You cannot measure a
  postgres the way you measure a jar, and "disk usage" means something different for
  each. Today classification's output is *persisted enums*; what a probe actually needs
  from it is *which command to propose*.
- **The MCP surface can only enumerate action ids.** An agent cannot ask what an app
  answers to, and cannot declare an app it has evidence for (a crontab entry it just
  read). Both are natural things for a model to do, and both are currently impossible.

## Decisions taken (review 2026-08-12)

Four axes were settled; they are recorded here because the remaining options depend on
them. This stays a concern — the open questions below are load-bearing.

1. **A compose project is one app; commands may target a part.** `app-name` stays a
   **flat** namespace (`shop`); a command may additionally declare the **part** it acts
   on (`ui`, `worker`, `db`). The card carries app-level controls *and* per-part
   controls. No app-inside-an-app, no nested namespace. A project's own dedicated
   postgres is simply another part.
2. **Classification survives as a small declared label, stamped once.** Discovery
   classifies in order to choose the right probe, and stamps a short `role` /
   `dedication` label onto the proposed action. Declared at proposal, **never
   recomputed at read time**. 032's vocabulary survives as a *declaration*, not as a
   persisted classification model that the read path re-derives.
3. **The verb vocabulary is closed.** `start · stop · restart · deploy · status · logs ·
   metrics` get real controls and are what MCP speaks. Anything else (`reindex`,
   `migrate`, `backup`) is still registered and runnable — it just renders as an
   ordinary run chip, not a card button. Keeps "what can I do with this app" a closed
   question a model can rely on.
4. **A declared app is first-class.** An app may exist because a human or an agent
   declared it, with no detected process behind it; the monitor shows it as *declared —
   no process seen* until one of its commands proves otherwise. This is the only route
   by which portless workloads ever become visible.

## The model these imply

```
app          a name. A namespace, not an entity — spec-022's declined `App`
             entity stays declined.
contract     the set of verbs its APPROVED commands declare. Derived, and
             PARTIAL BY DESIGN — most real apps will answer `start` and
             nothing else. It is a capability set, never an interface that
             something must implement.
command      a verb implementation, carrying declarations: app-name, and
             optionally part / verb / role / dedication.
classify     a proposal-time input whose OUTPUT IS COMMANDS. Classify once,
             deeply, at discovery; emit the right probe; never classify again.
reading      declares its own unit and whether it is attributable — a shared
             global's number cannot be split per project, which 038/041
             already say out loud ("shared engine, resource not split per app").
```

The taxonomy the review converged on is close to what 032/033/038 already encode, which
is good evidence the model is right; the question was never whether it is correct, but
**where it lives and when it runs**. It separates into two orthogonal axes plus a
grouping:

| Axis | What it decides |
|---|---|
| **Packaging** — native (jar / python / bash) · docker single · compose project | how you *measure* (`/proc` vs `docker stats`) and how you *control* (script/systemd vs docker) |
| **Role** — workload · global service (mysql, mariadb, postgres, nginx) | what a useful metric even *is*, and whether the number is attributable |
| **Dedication** — shared · single-dependent | whether one project may claim the resource |
| **Grouping** — compose project | an app that *is* several parts (ui, backend, worker, its own db) |

## Hypotheses / Options

### A — Where the declarations live

- **A1 · Reserved correlation-only params** (the spec-026 `app-name` precedent).
  *Pro:* proven, gate-safe by construction — never reaches argv, so S4 is untouched, and
  `targetApps` already demonstrates the read pattern. *Con:* params start carrying
  metadata they were not designed for; four reserved names is a lot of convention.
- **A2 · Real columns on `Action`.** *Pro:* honest — a declaration is not a param.
  *Con:* a migration, DTO surface, and it edges back toward the persisted model 040
  objects to (though a declaration ≠ a re-derived classification).
- **A3 · Naming convention only** (today's approach, extended). *Pro:* zero mechanism.
  *Con:* this is exactly what produced 050's name-collision crack.

### B — Do declarations enter the approval hash?

Concern 036 records the asymmetry: narrowing `APP_PORT_LIST` is gate-free, but the
`app-name` `ALLOWED_SET` **is hashed** into the snapshot.

- **B1 · Hash them.** Re-classification, a renamed app, or a corrected verb all require
  re-approval. Safe, and consistent with what `app-name` already does — but noisy, and
  it makes every discovery refinement a human interruption.
- **B2 · Leave them out.** Declarations can be corrected under an approved action.
  Fast — but a verb is now a thing that changes what a button *does* without passing the
  gate. Needs a hard argument that it is not a gate hole.
- **B3 · Split.** Identity (`app-name`, `part`) hashed; presentation (`role`,
  `dedication`) not. Plausible, needs the line defended.

### C — What happens to 032 / 033 / 038

- **C1 · Convert.** The persisted enums become declarations stamped at proposal; the
  three specs get amended or superseded per the catalog convention.
- **C2 · Coexist.** Declarations are added; the enums stay authoritative during a
  transition. Lower risk, but the double-derivation persists for as long as the
  transition does — which is 040's complaint, deferred rather than answered.

### D — Probe selection under S4

If discovery proposes a *different* probe per role (a postgres probe, a `/proc` probe, a
`docker stats` probe), each is a fixed source-controlled script constant (S4 forbids
variable command bodies). That is N constants for N roles, growing with the taxonomy.

- **D1 · One constant per role.** Explicit and pinnable; the table grows.
- **D2 · One constant with an internal detector table** (the shape 049 already chose for
  its runtime detector). Fewer constants; a bigger script to review at approval.

### E — Retiring a declared app

A declared app that is never seen, and a detected app whose process vanished, are the
same problem from opposite ends. Concern **036** already records that nothing retires a
vanished resource — it lingers, including runnable APPROVED actions, and revoke is the
only stop. Making apps declarable makes that sharper: an app can now exist with *no*
runtime evidence by design, so "not seen" can no longer imply "stale".

### F — The MCP surface

- **F1 · Verb-level tools** (`app_verb(app, verb)`) — the shape that makes the closed
  vocabulary pay off for an agent.
- **F2 · Keep action-id tools**, with the contract exposed as a read.

**Either way there is an S9 collision to resolve.** spec-049 derives app identity from
filesystem paths, and 049's own Known Gaps state: *"Folder paths are UI-only… must not be
added to any MCP view — the S9 line (spec-028) extends to filesystem paths."* If app
names are path-derived **and** apps become an MCP concept, that gap is violated by
construction. Either derived names are sanitised, or naming becomes a human/agent act at
approval time.

## Open Questions

1. Where do declarations live — reserved params (A1), columns (A2), or convention (A3)?
2. Do they enter the approval hash (B)? If split (B3), what principle draws the line
   between identity and presentation?
3. Convert 032/033/038 or coexist (C)? If convert, which specs get a WARNING and which
   get superseded?
4. How many fixed probe constants is the role table worth (D1 vs D2), and who reviews a
   detector-table script at approval?
5. What retires a declared-but-never-seen app, and does that answer also fix 036's
   vanished-resource problem — or does 036 have to land first?
6. Does the MCP surface speak verbs (F1)? And how are path-derived app names kept off it
   without losing the identity 049 works to establish?
7. Is `part` addressable by MCP as well as the UI ("restart the worker"), or is
   part-level control UI-only in v1?
8. Does a hand-declared app need a *machine* to exist against, or can it be declared
   ahead of one?
9. Do 049 and 050 rebase onto this concern's outcome, or build on today's conventions
   and accept the rework? (See *Impact on 049 and 050* below.)

## Impact on 049 and 050

Both were red-teamed on 2026-08-12 (4 blockers, 6 risks, 5 minors across the two); this
concern touches two of those findings directly:

- **050's verb-by-name inference is superseded** by a declared verb, which dissolves its
  name-collision gap rather than patching it.
- **049's job changes shape**: it stops being "detect a folder and some sizes" and
  becomes "produce declarations (app-name, part, role) *plus* sizes" — its detection work
  is unchanged, its output contract is not.

Neither is blocked *today* — both can build on the current conventions and be rebased —
but the choice should be explicit: rebase 049/050 onto whatever this concern lands, or
proceed and accept the rework. That is Open Question 9.

## Related

- **040** — the concern this absorbs (runs-invisible half stays there; "what is an app"
  moves here). Its Open Questions 1 and 6 are restated as A/C above.
- **022** — deliberately declined a first-class `App` entity in favour of a label
  convention. **This concern does not reverse that**: an app stays a name plus
  declarations, never a table.
- **026** — the reserved `app-name` correlation-only param: the precedent every option
  in A is measured against.
- **025 / 049 / 050** — the port-driven detection chain and the blind spot it creates.
- **032 / 033 / 034 / 038 / 041** — the taxonomy, the compose grouping, the categorical
  consumer palette, dedicated-vs-shared, and attributability (`OTHER = host_used − Σ
  attributed`).
- **036** — retire / suppress, and the `app-name` `ALLOWED_SET` hashing asymmetry that
  option B turns on.
- **007 / 015** — the command shape and the content-pinning that keys on
  `RecipeType.CUSTOM`.
- **008 / 028** — the MCP tool surface and the S9 host-hiding line option F must respect.
- **052** — a file-transfer package aimed at *an app* (its config dir, via 049's
  `appRoot`) is a far more reviewable thing than one aimed at a raw path; if this concern
  lands, 052's destination model should be revisited in its light.
- **ARCH.md** — S4 (fixed script bodies, typed params, no free-form commands), S5
  (login-user coverage), S9 (MCP host/path hiding).
