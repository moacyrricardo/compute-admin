# 054 — Lightweight app-metadata model

> **WARNING — resolved by specs 055–060 (the app-model epic).** The five forks were decided
> 2026-08-27 (all recommended) and the model graduated into: **055** foundations & mapping · **056**
> discovery · **057** footprint probing · **058** standalone-DB sizing · **059** UI (new surfaces) ·
> **060** MCP surface audit. This concern stays as the record of the clean-slate model and the
> decisions; the *buildable* detail now lives in those specs. It also resolves 053's identity half
> (see 053's WARNING).

**Status:** concern · no branch · no Linear (blocked for this repo; tracked as `spec-054`).

> **Design reference:** [`054-assets/mockup-compute-admin.html`](054-assets/mockup-compute-admin.html)
> — a clickable, three-identity mock of the three surfaces this model feeds (add-machine,
> script/recipe discovery, machine dashboard). Two companion analysis artifacts sit beside it:
> [`054-assets/lightweight-app-model-report.html`](054-assets/lightweight-app-model-report.html)
> (the clean-slate **feasibility report** — the evidence behind every option below) and
> [`054-assets/lightweight-app-model-decide.html`](054-assets/lightweight-app-model-decide.html)
> (a **decision surface** that renders the five open questions as forks and emits an execution
> prompt). The report is the source of truth for the mechanisms; the decide surface is the source
> of truth for the open forks.

> **Supersedes the framing of 049 and 050.** This concern re-opens, from a clean slate, the
> question those two `todo` specs had already answered in code-shaped detail. Both are being closed
> out as superseded (a SUPERSEDED header points here); their mechanisms are not lost — they are
> re-examined as options below. It also **partially supersedes 053**: 054 absorbs 053's
> *identity / measurement* half, leaving 053 narrowed to the **verb & command contract** (the cut is
> in [Relationship to other documents](#relationship-to-other-documents), provisional on OQ1/OQ4).
> This concern is only about *what an app is* and *how we measure it*.

## Problem

compute-admin already discovers apps and attributes CPU/RAM/disk to them (the 032–041 epic), and
049/050 extended that with native app-folder detection and lifecycle-script controls. But the
existing framing grew **entangled**: 049 threads a `footprint` probe through the spec-032 `cpu`-check
idiom and joint-edits spec-041's `computeOther`; 050 stacks on 049 and couples app *identity* to the
verb/approval model; 053 carries a 17-decision verb contract on top. Each is individually reasoned,
but together they make "**what is an app, and how do we size it**" hard to see and hard to change —
which is exactly the question the operator wanted to settle first, on its own terms.

So this concern steps back to a **lightweight, purely-mechanical model**, deliberately independent of
what is already built or spec'd, and asks only what is *true and reachable over SSH* on a real Linux
host. The unit is a small triple — **an app-script, the folder it lives in, and the app/context
folder that owns it** — and from that triple alone we want to (a) **group** scripts under a context
and (b) **probe** that context for disk, RAM, CPU, and (for Docker) database usage.

The feasibility report's headline: the model **holds and is largely deterministic**, and **~80 % of
the map is obtainable as an ordinary login user** — `sudo` buys only *other users'* process internals
and Docker/volume internals, and every privileged probe has a labelled, degraded fallback. Almost
everything in the pipeline is *mechanism* (fixed by Linux); only a handful of genuine **choices**
remain (the Open Questions below).

## Hypotheses / Options

The model is **one pipeline** — Discovery → Mapping → Probing — with a Docker/common-service layer
that feeds all three rather than sitting in the chain. Full commands, cost, and privilege per stage
are in the [feasibility report](054-assets/lightweight-app-model-report.html); summarized here.

### The pipeline (the parts that are mechanism, not choice)

- **Discovery.** Two sweeps, unioned: listening **ports** (`ss`/`/proc/net/tcp` → PID → `exe`/`cwd`/
  `cmdline`) and **Docker** (`docker ps`/`inspect`). Load-bearing rule: read `/proc/<pid>/cgroup`
  *before* trusting `exe`/`cwd`, or a container's overlayfs path masquerades as an app-folder. Two
  blind spots that must be handled: DNAT/`docker-proxy` ports, and **non-listening** apps (workers,
  cron) — see OQ4.
- **Mapping.** From a discovery record derive the **script-folder** (where it lives) and the
  **app-folder / context** (who owns it) with a fixed **wrapper-directory rule**: a script directly
  under `scripts`/`bin`/`sbin`/`frontend`/`backend`/… collapses to the *parent* of that wrapper;
  otherwise the context is the script-folder itself. This is what makes `/opt/lab/app1/scripts/run.sh`
  and `/opt/lab/app1/migrate.sh` share one context. Hop depth and symlink handling are the two
  choices (OQ1, OQ2).
- **Probing.** Per-context measurement, each axis a *different kind* of thing: **disk** = per-context
  state (`du` on the app-folder minus mount sources, plus volumes; `df` for headroom); **RAM** =
  per-script instantaneous **PSS** summed to the context (never RSS in a sum); **CPU** = a *rate*
  (Δ`utime+stime` over one interval, sampled in a single SSH exec); **DB** = two numbers, logical +
  physical, Docker-only for now (OQ5). Three cadence tiers keep it cheap (procfs 30–60 s / docker
  1–5 m / `du` hourly+).
- **Docker & common-service extraction.** The operator's "maybe we need a Dockerfile parser"
  resolves cleanly: **don't parse source — inspect the live engine.** `docker inspect` /
  `docker compose config` return *resolved* facts (real host paths, published ports, effective env);
  the only dependency adopted is the Docker CLI already on the host. A default-folder catalog covers
  nginx / postgres / mysql / mariadb (mongo near-future). A **dockerized** DB shares its compose
  project's context; a **standalone** DB is its own context.

### How this relates to what 049/050 already chose

Each 049/050 mechanism reappears here as an *option*, not a given:

- 049's port→PID→cmdline→cwd chain **is** this model's Discovery+Mapping — but re-based on the generic
  wrapper-dir rule rather than per-language detector tables (Java/Python/…). Open: whether the
  language-specific deploy-root detection survives as a refinement or is dropped for the uniform rule.
- 049's `footprint` probe folded into spec-041's `computeOther` **is** this model's disk axis — the
  double-counting rule (a volume/bind under an app-folder counted once) is the same tension, stated
  generically.
- 050's `LifecycleDiscoverer` / "register script *files*, never infer a run-command" constraint is
  orthogonal to *this* concern (it is a **verb** question → 053) — 054 only needs the *grouping* of
  those scripts under a context, not their run semantics.

## Decisions taken (locked 2026-08-27)

All five forks were resolved through the
[decision surface](054-assets/lightweight-app-model-decide.html); each took its recommended option.

1. **Symlink identity key (D1 → the split).** Dedup contexts on the resolved *physical* path,
   **display** the logical path, and **promote** the key to the logical path when the physical path
   matches `*/releases/*` or `*/versions/*` (so a redeploy doesn't fork a new context). This is the
   token 053's red-team flagged as *worsened*; **spec-015's pinned argv and S9 sanitisation must
   adopt this same key** (a coordination rider, below).
2. **Wrapper-dir hop depth (D2 → one hop + marker-gated second).** Single hop by default; a bounded
   **second** hop only when the intermediate dir is also a wrapper *and* the candidate carries a
   marker file (`.git`/`compose.yaml`/`package.json`). Cap at 2 hops; the boundary clamp always
   applies. Git-rooted monorepos group at the repo root — documented as intended behaviour.
3. **Privilege posture (D3 → degrade-and-label).** Without sudo, degrade gracefully and label every
   degraded number — RSS as an upper bound (not PSS), cgroup instead of per-PID, `permission-denied`
   instead of a fake `0`, `confidence=low` on procfs-denied mappings; never read "no PID" / a blank
   users-column as "no process". **Operator addition (D3 note):** expose an on-demand **"re-probe
   with sudo"** that upgrades a degraded reading to full fidelity (PSS, per-PID, other-user paths).
   *(Real work — see below.)*
4. **Non-listening apps (D4 → in scope).** The `systemctl`(running) + cron enumeration + `ps -eo
   args` interpreter sweep runs alongside ports+docker; non-listening apps emit the same record with
   an empty port list. **Consequence:** 053's "structurally undetectable" population becomes
   discoverable, so 053's declared-app machinery dissolves and the **054↔053 partial supersede
   holds** (see Relationship).
5. **Database sizing (D5 → docker-only now).** Logical (`docker exec` size query) + physical
   (data-volume `du`) for dockerized DBs. **Standalone (non-docker) DB sizing is deferred to its own
   future spec** (same SQL, host-side transport) — created via `/spec-workflow:new` when scheduled
   (D5 note). *(Deferred spec — see below.)*

## Open Questions

**All five resolved on 2026-08-27 (see *Decisions taken*).** What remains is not open questions but
**work** — none of it done by recording these decisions:

- **Graduate this concern into a build spec** (`/spec-workflow:new`) — the implementable
  discovery → mapping → probing model. That spec resolves 054 (adds the WARNING here) and is where
  the real code lives.
- **A deferred spec for standalone (non-docker) DB sizing** (D5 note) — its own `/spec-workflow:new`.
- **Two flagged real-work riders:** the on-demand *re-probe with sudo* action (D3 note), and having
  **spec-015's pin path + S9 sanitisation adopt D1's symlink key** — the coordination the 053
  red-team demanded (one decided token, adopted by all its masters, not just declared once here).

## Relationship to other documents

- **049 / 050** — superseded by this framing (SUPERSEDED headers point here); their code-shaped
  mechanisms are the options above.
- **053 (app model & verbs)** — **054 partially supersedes 053.** A review comparing the two models
  found that ~10 of 053's 17 decisions are *identity / measurement* calls that sit on 054's side of
  the seam and dissolve or move here; 053 survives **narrowed to the verb & command contract**, whose
  complexity is forced by the **approval gate** (spec-004/015) — which 054 deliberately does not touch —
  not by the identity model.
  - *Move to 054 (identity/measurement half):* declared-app substrate + lifecycle (053 dec. 4, 8, 9,
    10, axis E, OQ2, OQ3 — its OQ4 non-listening-apps sweep makes 053's "structurally undetectable"
    population *discoverable*, removing the declared-app rationale); classification-as-declaration
    (dec. 2, the A4 side-data half of dec. 5); path-derivation mechanics (dec. 12, mapping); the
    normalisation + alias map (dec. 13, now dedup-by-construction); the probe-constant/detector table
    (dec. 16, axis D); the 032/033/038 transition end-state (axis C); the compose-project = one app
    *identity* call (dec. 1, identity half); and the **symlink-vs-resolved** token (053's "worsened"
    finding) → **054 OQ1**.
  - *Stays in 053 (verb & command contract):* the closed **verb vocabulary** (dec. 3), identity+verb
    **inside the approval hash** and re-approve-on-change (dec. 5 A1 half, dec. 7, its destructive
    flag), the **never-in-argv** invariant (dec. 6), multi-app commands (dec. 11), MCP-speaks-verbs
    and `part` addressing (dec. 14, 15), "agent proposes / UI decides" (from dec. 8), the
    accept-at-approval rider (dec. 12), and OQ4/OQ5.
  - **The cut was load-bearing on 054's OQ1 + OQ4 — now decided (2026-08-27).** OQ1 took the split
    symlink key and **OQ4 landed IN SCOPE**, so 053's declared-app machinery dissolves and this
    partial supersede **holds** (had OQ4 gone "ports + docker only," it would have walked back in).
    The full 053 retitle + WARNING lands when 054 **graduates into its build spec** — a concern is
    retired by a resolving spec, not by another concern.
- **032–041** — the consumer/footprint axes 054's Probing stage feeds; 041's single-denominator
  `computeOther` is the disk-axis integration point.
- **040 (monitor runtime view & model weight)** — 054's "mechanism, not server-side classification"
  posture is the thin-BE leaning 040 argued; a graduating 054 spec should honor it.

## Notes

- **Clean-slate on purpose.** The report and decide surface were authored *independently* of the
  current specs/code, to judge the model on Linux feasibility alone. The catalog entanglement is the
  reason; do not re-import 049/050's coupling without a deliberate choice.
- Everything produced so far is **read-only analysis** — no code, no gate change. Graduating this
  concern into a spec is where implementation choices (some of which commit real schema/probe work,
  per the decide surface) get made.
