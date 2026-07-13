# 038 — Compose-project grouping in the fleet view

> **Status: todo.** Stacked on `moacyrricardo/spec-037-docker-consumer-metrics`
> (merge after spec-037). Builds on the discovery of
> [033](./033-done-docker-container-discovery.md)
> (`DockerComposeDiscoverer`, on main), the fleet UI of
> [034](./034-done-fleet-monitor-ui-redesign.md), and the docker metric poll of
> [037](./037-done-docker-consumer-metrics.md). No Flyway migration. Linear blocked
> (no ticket).

## Context

spec-033 correctly **detects** compose projects: it groups containers by the
`com.docker.compose.project` label and classifies each project's own datastore as
`DEDICATED` with `owner=<project>`. Detection is not the problem — **rendering** is.

spec-033's `DockerComposeDiscoverer.projectRecipe` emits a project's dedicated
datastores as **separate top-level consumers**, siblings of the project's app
consumer. So a project like `lia-consulta` — whose app consumer lists services
`[worker, api]`, with `postgres` and `redis` as its dedicated datastores — surfaces
in the fleet **Apps** view as **three floating sibling cards**: `lia-consulta`,
`lia-consulta-postgres`, `lia-consulta-redis`. That reads as "three unrelated
things," not "one composed application," and it diverges from the agreed design mock
([`docs/fleet-resource-mock.html`](../docs/fleet-resource-mock.html)), where a compose
project is **one card** with its datastore as a **service inside it**, and the
dedicated/shared split surfaces only in the **Databases** lens.

The read layer (`MonitorConsumerView`), the client card/legend/drawer, and the docker
poll (spec-037) all already work per-consumer and per-service — so the fix is a
grouping decision at the discovery seam plus the Databases-lens derivation on the
client. Nothing about detection, the gate, or the schema changes.

## Decision

1. **A compose project = ONE consumer / ONE card.** Its `services[]` carries **all**
   of the project's services — app services *and* datastore services — each tagged
   with its `role` (`APP` / `DATABASE`). A project's dedicated datastore is a
   **service of that project**, **not** a separate top-level consumer. A
   `role=DATABASE` service inside a project consumer *is* the dedicated-datastore
   signal (dedicated to that project); the per-service dedication meaning is carried by
   the service's role + its parent project, so no per-service dedication field is
   needed.

2. **Standalone / shared datastores and datastore-only projects are unchanged.** A
   datastore started outside any compose project (a bare `docker run redis`) stays its
   own `SHARED` `DATABASE` consumer. A compose project that contains **only** datastore
   services (no app service) stays one `SHARED` `DATABASE` consumer for the project.

3. **The Databases lens derives the Dedicated band from services, not consumers.** The
   **Dedicated** band is built from every project (non-`DATABASE`) consumer's
   `role=DATABASE` **services** — `owner` = the project — one slice per datastore
   service, preserving the per-datastore axis split. The **Shared** band stays the
   top-level `DATABASE` consumers (standalone datastores + datastore-only projects). It
   remains a *re-slice* of the same containers the Apps view shows, not a move.

## Implementation

No server read-side change (the read layer maps whatever consumers/services it is
given), **no Flyway migration**, gate / `mcp/` / every `*ArchTest` untouched, and the
client stays `textContent`-only (the `h()` no-`innerHTML` invariant).

- **`DockerComposeDiscoverer.projectRecipe`** (`discovery/service`). For an **app**
  project (one with at least one non-datastore service), emit a **single**
  `DockerConsumer(project, APP, dedication=null, owner=null, usedBy=[], bucket=null,
  services=[ALL services incl. datastores, each with its role])`. **Drop** the separate
  per-datastore `DEDICATED` `DATABASE` consumers. A **datastore-only** project still
  emits one `SHARED` `DATABASE` consumer; `standaloneDatastoreRecipe` / `bucketRecipe`
  are unchanged. The dedicated/owner meaning is preserved on the datastore **service**
  (a `role=DATABASE` service inside a project ⇒ dedicated to that project).

- **Rendering (`app.js`).**
  - *Apps view* already renders one card per consumer, so the project now renders as
    one card. Its axes already sum `services[]` (spec-037's `applyDockerReading`), which
    now includes the datastore services — so the project card's RAM/CPU/disk sum **all**
    its services, app and datastore. No change needed beyond the data shape.
  - *Drawer* already lists `services[]`; the datastore services now appear alongside the
    app services. Tag a `role=DATABASE` service row so a datastore reads as a datastore
    inside the project.
  - *Databases lens* — rework `datastoresOf(consumers)`: the **Dedicated** band derives
    from each non-`DATABASE` consumer's `role=DATABASE` **services** (owner = the
    project, each with its own per-service axes); the **Shared** band stays the top-level
    `DATABASE` consumers. `splitMeter` keeps the per-datastore split.

- **Docker poll (spec-037, `app.js`).** Retain **per-service (per-container)** metrics
  so the dedicated split can show each datastore's %-of-host; the project card's axes
  still sum all of its services. `applyDockerReading` already fills both — unchanged.

- **Tests.** Update `DockerComposeDiscovererTest` (an app project → **one** consumer
  whose services include the datastores, each tagged with its role; **no** separate
  dedicated consumers), `DockerComposeReconcileTest` and `MonitorRollupTest`'s docker
  assembly (the new grouped shape). Extend the headless render check
  (`src/test/js/docker-consumer-metrics.render-check.js`) to assert a project renders as
  **one** card whose services include its datastore, that the card's axes sum all
  services, and that the Databases lens still shows the dedicated split derived from the
  project's `role=DATABASE` service.

## Known Gaps

- **Per-service axis attribution** relies on per-container `docker stats` (spec-037);
  a container whose `MemUsage`/`CPUPerc` doesn't parse degrades that service's axis to
  `—` and it is simply omitted from the project's sum (never a silent 0).
- **Shared-volume size attribution** stays best-effort: `docker system df -v` gives a
  volume's size and a link count, not which container uses it; named volumes are
  attributed to a project by the `<project>_…` name convention only (the spec-037 gap).

## Divergence from the spec (as built)

_(filled in on the final commit)_
