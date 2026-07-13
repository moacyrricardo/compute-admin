# 034 — Fleet monitor UI/UX redesign

> Builds on the consumer contract in
> [032](./032-todo-monitoring-axes-foundations.md) and evolves the fleet dashboard of
> [029](./029-done-fleet-monitoring.md). Renders richer once
> [033](./033-todo-docker-container-discovery.md) lands, but is buildable against
> 032 with native data alone. **Design reference:**
> [`docs/fleet-resource-mock.html`](../docs/fleet-resource-mock.html) — an interactive
> mock in the real spec-012 design system (reviewed 2026-07); this spec is its written
> form.

## Context

The 029 fleet view shows per-app cards with a single **mem-% of host** bar. Once every
consumer carries three axes (032) and docker projects/datastores become consumers (033),
the dashboard should let an operator navigate **broad (machines) ↔ deep (apps)** and see,
at a glance, **how the box's RAM/CPU/disk are shared and competed for** — which the
single-metric card can't express.

The redesign is a UI/UX evolution: it stays in the spec-012 design system (theme-aware,
`h()` textContent-only, tokens/components verbatim) and adds only what the system lacks.

## Decision

1. **Every consumer sits on the same three axes as its host: RAM · CPU · Disk, as a % of
   that machine.** The machine's host panel becomes **three segmented bars** (RAM/CPU/
   disk); each bar is sliced per consumer, **one colour per consumer held constant across
   all three axes**, so "the blue chunk" tracks a consumer from RAM to CPU to disk. The
   `meterBand` thresholds (amber ≥75, red ≥90) colour the aggregate "used %".

2. **A categorical consumer palette is the one new token group.** spec-012 deliberately
   has no data palette (semantic-only). Add ~5 categorical hues + a neutral for the
   `DOCKER` bucket to `tokens.css`, **always paired with a labelled legend** so colour is
   never the sole signal (WCAG AA, the house rule). This is the sole visual addition.

3. **Per-consumer cards carry all three axes** (029 had only mem), plus the existing
   framework badge, UP/DOWN pill, checks chips, and ops chips. Native apps render disk
   `—`. A springboot-in-docker consumer (033) shows **once**, docker-sourced.

4. **Two hidden buckets keep the bars honest.** `DOCKER` (unclassified) and `SYSTEM`
   (OS + free) are hidden by default via `.tag--filter` toggle chips; revealing them fills
   the bars to 100 %. Default view = named consumers only, bars deliberately short of 100 %.

5. **A "databases" lens (View: Apps | Databases), decided as one lens with two bands.**
   Toggling to Databases re-slices the *same* consumers by role (a re-slice, not a move —
   the DB still lives under its app in the Apps view):
   - **Dedicated** band — datastores owned by one app; shown with the **owner split** per
     axis (e.g. mysql 30 % / psql 70 % — and the split legitimately *differs per axis*).
   - **Shared** band — datastores used by many, no owner; shown as rows with `used by …`
     chips and **no per-app split** (a shared engine's resource can't be honestly
     attributed to one app). Native shared datastores show disk `—`.
   *(This closes the one-lens-vs-two-lenses question: one lens, two bands. Revisit only if
   the two-band split proves confusing in use.)*

6. **Probe honesty.** A card/drawer shows **only the probes that actually responded** — a
   springboot exposing just `/actuator/health` shows only health; an axis with no
   approved source shows `—` / an "approve this monitor to see" hint (the 029 pattern).

7. **The filter set is still the poll set** (029): tag + app-name filters, `no-apps`
   host-only view; filtered-out is never polled. The lens/bucket toggles are pure client
   re-renders of already-polled data.

## Implementation

All changes cite `spec-034`, in `src/main/resources/static/` (spec-012 idiom: `h()`,
`meterBand`, `.meter`, `.app-card`, `.pill`, `.fw-badge`, `.run-chip`, `.drawer`,
`.filter-chips` — all reused verbatim).

- **`tokens.css`** — add the categorical palette (`--c-1..--c-5`, `--c-docker`) with
  light/dark values; nothing else changes.
- **New render helpers** (mirroring the mock): `axisMeter(label, consumers, axis)` — a
  segmented `.meter-track`; `legend(consumers)` — clickable legend chips (open the
  drawer); the databases-lens `splitMeter`/shared-row renderers built from `.card`/
  `.meter`/`.chip`.
- **`screenMonitor` / `buildMachineSection`** consume `MonitorConsumerView` (032) instead
  of `MonitorAppView`; host panel → three `axisMeter`s + a per-machine legend; app grid →
  cards extended to three axes. Add the **View (Apps | Databases)** and **Show (docker |
  system)** chip groups to the controls row.
- **Drawer** (`openAppDrawer` → `openConsumerDrawer`) gains the tri-axis readout, a
  services breakdown (per-service axes + image + badges), the probe list (only-available),
  and the compose-file block (path, or "not reachable from this host" for docker).
- **Retire the 029 `MonitorAppView`** on the client once the switch is complete; the
  server keeps it only as long as 032's transition requires.
- Theme-aware and `textContent`-only throughout; no `innerHTML` (spec-012 invariant,
  enforced by `h()`); gate untouched, `mcp/` untouched.

## Known Gaps

- **No fleet aggregate banner** ("N/M consumers over 75 %") — per-machine sections carry
  state; a rollup strip is deferred (029/031 gap).
- **No history / trend** — snapshots only (029/031).
- **Colour count** — with more than ~5 named consumers on one machine, categorical hues
  recycle; the legend disambiguates, but a large fleet may want hashing/greying of minor
  consumers (deferred).
- **Disk axis is sparse without 033** — built against 032 alone, disk is `—` for
  everything native; the axis is fully meaningful only once docker discovery lands.
- **The mock is illustrative** — final spacing/wording is the build's to refine; the
  committed `docs/fleet-resource-mock.html` is the design intent, not a pixel contract.
