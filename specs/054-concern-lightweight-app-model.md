# 054 — Lightweight app-metadata model

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
> re-examined as options below. 053's **verb / action contract** is a *separate* axis and stays its
> own concern; this one is only about *what an app is* and *how we measure it*.

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

## Open Questions

The five genuine forks, carried in full (with evidence + a recommended option) on the
[decision surface](054-assets/lightweight-app-model-decide.html). Each must be settled before 054 can
graduate into a spec.

1. **Symlink identity key** — when a script/binary is reached through a symlink, does the context key
   on the resolved *physical* path, the *logical* path, or a split (dedup on physical, display
   logical, promote release-symlinks to logical)? *Widest downstream blast radius.* This is the exact
   token 053's red-team flagged as **worsened** (symlink-vs-resolved), so 054 owns resolving it.
   *Report lean: the split.*
2. **Wrapper-dir hop depth** — one hop, a marker-gated bounded second hop, or an unbounded walk to
   the git root? Governs whether nested monorepo scripts reach the repo root. *Report lean: one hop +
   marker-gated second.*
3. **Privilege posture** — degrade-and-label without sudo (the ~80 % path), require sudo/root, or
   unprivileged-only? *Report lean: degrade and label every degraded number.*
4. **Non-listening apps** — in scope via a mandatory `systemctl`/cron/`ps` sweep, ports+docker only,
   or opt-in per machine? Ports+Docker alone can't see workers/cron/batch. *Report lean: in scope.*
5. **Database sizing scope** — Docker-only now (standalone a documented future hook), include
   standalone now, or drop the DB axis? *Report lean: Docker-only now.*

## Relationship to other documents

- **049 / 050** — superseded by this framing (SUPERSEDED headers point here); their code-shaped
  mechanisms are the options above.
- **053 (app model & verbs)** — the **verb / action / "destructive"** contract stays there; 054 is
  only *identity + measurement*. The two meet at one seam: 054's chosen app-identity (OQ1) is what
  053's declared-app / verb params attach to. 053's own open item "where a declared app persists"
  depends on 054 settling what an app *is*.
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
