# 035 — Discovery enablement & UX

> Closes doubt **(1)** of [concern 030](./030-todo-docker-container-monitoring.md)
> (should docker discovery be gated behind `docker ps` being allowed?). Gates the
> [docker discoverer](./033-todo-docker-container-discovery.md) and generalises to the
> existing `NGINX`/`DOCKER`/`DATABASE`/`CRON`/`SYSTEMD` discoverers (spec-006/026).
> Independent of the axes/UI work (032/034); sequence it after 033 so there is a docker
> discoverer to gate.

## Context

Today discovery **probes freely** as the SSH login user and only the *proposed recipes*
are gated (approval = UI-only, spec-004). That is fine for `ss`/`/proc` — a read the
login user could already do. **Docker is different:** talking to the docker socket is
**root-equivalent**, and the login user must be in the `docker` group (or use `sudo`) to
run `docker ps`/`stats`/`inspect` at all. So "may this tool poke docker on my box" is a
real *capability* decision, not a read-you-could-already-do — and it should be an explicit
opt-in, not something discovery does speculatively the first time it runs.

There is also no UI today that tells an operator *what discovery can do*, *which
discoverers are active*, or lets them turn a capability on/off.

## Decision

1. **Discovery capability is enabled per discoverer family, not per probe.** An operator
   enables **Docker discovery**, **Systemd discovery**, **Database discovery**, etc.
   — a group toggle, not individual `docker ps` vs `docker stats` approvals (too fine for
   no gain) and not recipe-depends-on-recipe (elegant but over-couples discovery to the
   execution-gate machinery). A discoverer that is not enabled is a **no-op**: it never
   probes.

2. **Docker discovery defaults OFF.** Because the docker socket is root-equivalent, the
   docker family is disabled until the operator explicitly enables it for a machine.
   Families that only run reads the login user already has (the current `ss`/`/proc`
   host + app discovery) stay **on by default** — this spec does not add friction to what
   already works; it gates the new root-equivalent capability.

3. **Scope: per-machine, with a fleet-wide default.** Enablement is stored per machine
   (a machine may have docker access on one box, not another) with a sensible
   account-level default (docker off). No new global namespace.

4. **This is enablement, NOT the approval gate.** Enabling a discoverer only lets it
   *probe and propose*; every proposed recipe still lands `PENDING_APPROVAL` and requires
   UI approval to *run*. The gate invariant is untouched (`GateArchTest`, `mcp/`).

5. **A discovery surface in the UI.** A per-machine "Discovery" panel: which discoverers
   are enabled, a toggle per family (docker guarded with a one-line "requires docker
   socket access; root-equivalent" note), the last run's proposals, and the existing
   "Discover recipes" action. Read of state is owner-scoped as everything else.

## Implementation

All new code cites `spec-035`.

- **Model + migration (travel together).** A `discovery_enablement` row per
  `(machine, discovererKey)` (or a JSON set on `Machine`), defaulting docker to disabled;
  Flyway `Vn` assigned at build time (next free then). Enum/keys align with the
  `RecipeDiscoverer` implementations' names.
- **`DiscoveryService.discover`** filters the discoverer list by the machine's enabled set
  before probing; a disabled family is skipped entirely (no probe, no proposal). The
  docker discoverer (033) therefore never touches the socket unless enabled.
- **API.** `GET /api/machines/{id}/discovery` (enabled families + last proposals) and
  `PUT …/discovery/{family}` (enable/disable) — RESTEasy `*RS @Component`, `@Secured`,
  owner-scoped via `requireMachine`. No MCP surface for enablement in v1 (it is a
  human/operator decision; keep it out of the LLM path — cf. spec-028/S9).
- **UI.** A "Discovery" section on the machine detail view (spec-012 idiom): a
  `.filter-chips`-style family toggle list, the docker note, and the proposals list.
  `textContent`-only, theme-aware.
- **Tests.** enabling/disabling gates whether a discoverer probes (fake discoverer asserts
  it is / isn't invoked); docker defaults off; enablement is owner-scoped (cross-user 404);
  the approval gate is unaffected (a proposed recipe still needs approval to run).

## Known Gaps

- **Detecting docker availability** (is the socket reachable / is the login user in the
  `docker` group?) is best-effort: enabling docker discovery on a box without access
  yields empty proposals + a surfaced probe error, not a hard precheck. A capability
  probe on enable is a possible refinement.
- **No auto-enable heuristics** — the operator opts in per machine; a "docker detected,
  enable?" nudge is deferred.
- **`sudo` for docker** vs docker-group membership is left to the login user's setup
  (S5 posture); this spec does not add a sudoers recommendation beyond ARCH S5.
- **Enablement is not a schedule** — it gates *whether* a family probes, not *how often*;
  cadence remains the connectivity/monitor job's concern.
- Generalising the per-family model to all discoverers is in scope, but only docker is
  default-off; revisiting whether any existing family should also be opt-in is deferred.
