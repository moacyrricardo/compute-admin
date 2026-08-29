# 067 — Machine-dashboard composition (Screen C)

**Status:** todo · Linear [BOL-896](https://linear.app/iskeru/issue/BOL-896) · build branch `moacyrricardo/bol-896-cpt-067-machine-dashboard-composition` · **Part of concern 064 (mockup delivery).**

## Context

The mockup ([`054-assets/mockup-compute-admin.html`](054-assets/mockup-compute-admin.html), Screen C
"dashboard", section at `:775`) shows a per-machine dashboard the live UI does not compose: one page
that stacks the machine's identity/status head, a **tri-axis consumer footprint** with legend
(`:801`–`:846`), a **recipes & actions** list with state chips (`:848`), **recent runs** (`:884`),
and an **SSH / connectivity** card (`:905`). The live `screenMachineDetail`
(`src/main/resources/static/app.js:607`) renders only identity + tags + the discovery section + the
recipe groups (`data.groups`, `app.js:642`); the footprint lives solely on the fleet Monitor route
(`screenMonitor`, `app.js:1776`), runs solely on `#/runs` (`screenRuns`, `app.js:1227`), and the SSH
key solely on the MCP/setup surfaces.

**Every ingredient already exists** — this spec is presentation-only composition, no server change:

- **Footprint.** `GET /monitor` (`MonitorRS.dashboard`, `monitor/api/MonitorRS.java:48`) already
  accepts a repeatable `?machineId=` scope ("the client's visible selection", spec-029) and returns
  `MonitorDtos.Dashboard` → `MonitorMachineView` (`monitor/api/MonitorDtos.java:62`, `:87`) with the
  spec-032 `consumers` list (`MonitorConsumerView`, `MonitorDtos.java:265`). The client already owns
  the whole render path: `buildSection`'s `paint()` composes `axisMeter` (`app.js:2458`) ×3 +
  `consumerLegend` (`app.js:2493`) + `consumerCard` (`app.js:2533`) over `.host-panel` /
  `.axis-track` / `.legend` (`app.css:514`, `:732`, `:744`), with `computeOther` synthesizing the
  OTHER/system segment (spec-041).
- **SSH card.** `MachineView` (`machine/api/MachineDtos.java:41`) carries
  `host/port/loginUser/status`; `GET /ssh/public-key` (`SshRS`, `ssh/api/SshRS.java:31`) returns
  `PublicKey(publicKey, fingerprint)` (`ssh/api/SshDtos.java:14`); the "Test connection" button with
  its chip swap already exists in `screenMachineDetail` (`app.js:627`–`:641`).
- **Recipes with state.** `actionsList`/`actionCard` (`app.js:675`, `:682`) render the spec-044 card
  grid with `chip(approvalState)` (`app.js:197`), sudo badge, and the split-approve control.
- **Filtering idiom.** The Monitor route's `chipBtn` (`app.js:1890`) + `.filter-chips`/`.tag--filter`
  (`app.css:237`, `:244`) are the house filter-chip pattern.
- **Recent runs.** The browser-scoped `ca.runs` cache (`Runs`, `app.js:91`–`:102`) already stores
  `machineId` per entry (`Runs.remember`, `app.js:1070`–`:1074`) — filterable per machine today.

Sibling boundaries: the **facts strip** (OS/kernel/uptime/cores/RAM/arch, mockup `:792`) needs data
that does not exist and is **068**; server-backed run history is **069** (optional); the
context-grouped discovery regroup of `data.groups` is **066** (blocked by
[063](063-todo-native-consumer-and-context-dto.md)); the visual identity is **065**. Design forks the
mockup opens against settled decisions (verb badges on action rows — no verb field in the model,
053/060 scope; declared apps — 053, unbuilt; footprint badges on discovery cards vs 059 Decision 1)
are recorded as Open Questions in **concern 064** and are not decided here.

## Decision

`#/machines/<id>` (`ROUTES`, `app.js:2989`) becomes the composed per-machine dashboard, built from
existing data and existing components:

1. **Per-machine tri-axis footprint + legend, from the existing Monitor read.** The machine page
   fetches `GET /monitor?machineId=<mid>` — the spec-029 scoping built for exactly this, so only the
   one machine is assembled and polled — and renders **the same** `axisMeter` ×3 (RAM/CPU/Disk) +
   `consumerLegend` + `computeOther` path the fleet Monitor uses (`app.js:2031`–`:2034`), one
   machine, no lens/tag bars. Consumer axes are filled by the same client poll loop (`refresh()`,
   spec-024/037 — no server sampler); intervals are cleared on route-away per the router contract
   (`app.js:3028`). No new endpoint, no new meter component, no fork of the paint path.
2. **SSH / connectivity card.** A `.card` with a `kv` list from the already-fetched `MachineView`:
   status chip, `loginUser@host:port` (mono), plus the app key identity from `GET /ssh/public-key`
   (`fingerprint`, key type prefix parsed client-side from `publicKey`). The existing "Test
   connection" button moves into this card (same `POST /machines/<mid>/test` + chip-swap logic,
   `app.js:627`). Mockup's "Handshake latency" and "last probed" rows need data that does not exist —
   **068**, rendered `—`-less (omitted) until then.
3. **Recipes-with-state list.** The recipe groups keep the existing `actionsList`/`actionCard`
   spec-044 card grid (state chip + sudo badge + split-approve) — **not** the mockup's flat rows with
   a bare Run button, which would bypass the review-safety guard (`approvalSplit`'s
   review-before-approve rule stays). The mockup's per-row verb badge and command echo are the 053
   verb fork — deferred to concern 064.
4. **Search box + type/source filter chips over the recipe list.** A text input (name/description
   substring, case-insensitive) plus `chipBtn` chips for recipe **type** (the distinct
   `g.recipe.type` values present) filter `data.groups` (`app.js:642`) **client-side** — pure
   re-render, no re-fetch, same posture as the Monitor lens toggles (spec-034 §7). A **source**
   chip dimension (native/docker) keys on 063's `AppPortView.runtime` (already carried once 063 lands); until then type-only.
5. **Recent runs, this-browser scope.** A "Recent runs" section renders `Runs.all().filter(r =>
   r.machineId === mid)` with the `#/runs` row idiom (`app.js:1231`–`:1237`) and the honest
   spec-005 caveat ("launched from this browser"). Server-backed history that survives the browser
   is **069** and swaps in behind this same section when/if it lands.

Layout: sections stack in mockup order (head → footprint → recipes → runs + SSH two-column grid),
collapsing to one column at the spec-043 breakpoints (`app.css:802`–`:836`); the two-column
runs/SSH grid is new CSS but token-only, no new colours.

## Implementation

All work in `src/main/resources/static/{app.js,app.css}`. Build via `h()` only (spec-012 XSS
discipline — no `innerHTML`); **restart `spring-boot:run` for every edit** (user memory
`dev-server-static-skew-on-branch-switch`).

- **`screenMachineDetail` (`app.js:607`).** Add `api("GET", "/monitor?machineId=" +
  encodeURIComponent(mid))` to the existing `Promise.all` (machine + recipes + discovery). From the
  response take `machines[0]` (owner-scoped; absent ⇒ omit the footprint section). Reuse the
  Monitor route's section-building internals — extract the single-machine `buildSection`/`paint`/
  `refresh` closure (`app.js:1976`–`:2060`) into a shared helper both `screenMonitor` and the
  machine page call, rather than duplicating the axis/legend/poll wiring. Poll cadence: reuse
  `MONITOR_CADENCES` with the default cadence, no cadence selector on this page (one machine; the
  "Run now" affordance is enough); register the interval with the route-away cleanup.
- **SSH card.** New small render fn `sshCard(machine, pubkey)`; fetch `/ssh/public-key` lazily in
  the same `Promise.all`. Reuse `.card` + the `kv`/`dl` idiom (`app.js:878` region) + `chip()` +
  `copyHostButton`. Move `testBtn` (existing logic unchanged) into the card footer.
- **Filter bar.** Above `groups`: an `<input>` (reuse the `.mono` input idiom, `app.js:1229`) and a
  `.filter-chips` row of `chipBtn`s from the distinct `g.recipe.type` values. Filtering is a
  client-side re-render of the groups container: a group matches when its recipe name/description or
  any action name matches the query AND its type chip (if any are on) is selected; actions within a
  matched group are not sub-filtered (the card grid stays whole — predictable approve targets).
- **Recent runs.** `Runs.all().filter(...)` sliced to ~10, rendered with the `screenRuns` list rows;
  link each to `#/runs/<id>`. Empty state via `empty()` (`app.js:287`) with the this-browser caveat.
- **CSS.** One new grid rule for the runs/SSH two-column section (collapsing ≤720px alongside
  `.host-panel`/`.action-cards`, `app.css:802`–`:836`); everything else reuses `.card`, `.section`,
  `.list`, `.filter-chips`, `.legend*`, `.meter*` classes verbatim. No mockup skin (`.fp-*`,
  `.facts`, rounded chips) is ported — identity is **065**.
- **Tests/evidence.** No server change ⇒ no Java tests move. Live evidence per CLAUDE.md "UI
  evidence": register machine → machine page shows footprint bars + legend, SSH card with
  fingerprint, filter chips narrowing the recipe cards, and (after one run) the recent-runs row.

## Known Gaps

- **Facts strip is 068.** OS/kernel/uptime/cores/RAM/arch (mockup `:792`) need a facts probe +
  storage that do not exist; this page adds the section only when 068 lands. Likewise handshake
  latency / last-probed on the SSH card.
- **Run history is browser-scoped.** `ca.runs` is a localStorage cache (`app.js:91`) — another
  browser sees nothing and eviction is at 50 entries. Server-backed history is **069 (optional)**;
  this spec deliberately ships the honest local slice first.
- **`GET /monitor` cost per machine-page visit (flag).** `?machineId=` scoping bounds assembly to
  one machine, but the read still walks the machine's full recipe/action tree and the page adds a
  poll loop per visit. If profiling shows the machine page makes `/monitor` hot, the fix is a
  server-side slim view — flagged for 064, not solved here.
- **Verb badges, command echo, and declared apps** on recipe rows are concern-064 Open Questions
  (053/060 scope — the model has no verb field); rows render name + state chip + sudo only.
- **Source (native/docker) filter chips** key on 063's `AppPortView.runtime` (already carried once
  063 lands) — no dependency on 066; until 063 lands the filter dimension is recipe type only.
- **The footprint numbers remain client-polled** (no server sampler, spec-029 gap): a freshly opened
  machine page shows `—` axes until the first poll cycle completes.
