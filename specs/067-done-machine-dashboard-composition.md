# 067 — Machine-dashboard composition (Screen C)

**Status:** done · Linear [BOL-896](https://linear.app/iskeru/issue/BOL-896) · build branch `moacyrricardo/bol-896-cpt-067-machine-dashboard-composition` · **Part of concern 064 (mockup delivery).**

## Context

The mockup ([`054-assets/mockup-compute-admin.html`](054-assets/mockup-compute-admin.html), Screen C
"dashboard", section at `:775`) shows a per-machine dashboard the live UI does not compose: one page
that stacks the machine's identity/status head, a **tri-axis consumer footprint** with legend
(`:801`–`:846`), a **recipes & actions** list with state chips (`:848`), **recent runs** (`:884`),
and an **SSH / connectivity** card (`:905`). The live `screenMachineDetail`
(`src/main/resources/static/app.js:607`) renders only identity + tags + the discovery section + the
recipe groups (`data.groups`, `app.js:642`); the footprint lives solely on the fleet Monitor route
(`screenMonitor`, `app.js:1793`), runs solely on `#/runs` (`screenRuns`, `app.js:1244`), and the SSH
key solely on the MCP/setup surfaces.

**Every ingredient already exists** — this spec is presentation-only composition, no server change:

- **Footprint.** `GET /monitor` (`MonitorRS.dashboard`, `monitor/api/MonitorRS.java:48`) already
  accepts a repeatable `?machineId=` scope ("the client's visible selection", spec-029) and returns
  `MonitorDtos.Dashboard` → `MonitorMachineView` (`monitor/api/MonitorDtos.java:64`, `:89`) with the
  spec-032 `consumers` list (`MonitorConsumerView`, `MonitorDtos.java:279`). The client already owns
  the whole render path: `buildSection`'s `paint()` composes `axisMeter` (`app.js:2476`) ×3 +
  `consumerLegend` (`app.js:2511`) + the Consumers `consumerCard` grid (`app.js:2594`) over
  `.host-panel` / `.axis-track` / `.legend` (`app.css:514`, `:740`, `:752`), with `computeOther`
  (`app.js:2454`) synthesizing the OTHER/system segment (spec-041).
- **SSH card.** `MachineView` (`machine/api/MachineDtos.java:41`) carries
  `host/port/loginUser/status`; `GET /ssh/public-key` (`SshRS`, `ssh/api/SshRS.java:31`) returns
  `PublicKey(publicKey, fingerprint)` (`ssh/api/SshDtos.java:14`); the "Test connection" button with
  its chip swap already exists in `screenMachineDetail` (`app.js:626`–`:640`).
- **Recipes with state.** `actionsList`/`actionCard` (`app.js:675`, `:682`) render the spec-044 card
  grid with `chip(approvalState)` (`app.js:197`), sudo badge, and the split-approve control.
- **Filtering idiom.** The Monitor route's `chipBtn` (`app.js:1907`) + `.filter-chips`/`.tag--filter`
  (`app.css:237`, `:244`) are the house filter-chip pattern.
- **Recent runs.** The browser-scoped `ca.runs` cache (`Runs`, `app.js:90`–`:102`) already stores
  `machineId` per entry (`Runs.remember` write, `app.js:1087`) — filterable per machine today.

Sibling boundaries: the **facts strip** (OS/kernel/uptime/cores/RAM/arch, mockup `:792`) needs data
that does not exist and is **068**; server-backed run history is **069** (optional); the
context-grouped discovery regroup of `data.groups` is **066** (now unblocked —
[063](063-todo-native-consumer-and-context-dto.md) is **done** on main: `RecipeView.appPortList`,
`AppPortView.runtime`, and `AppPortView.managementPort` all ship, `recipe/api/RecipeDtos.java:65`,
`:104`); the visual identity is **065**. Design forks the mockup opens against settled decisions
(verb badges on action rows — no verb field in the model, 053/060 scope; declared apps — 053,
unbuilt; footprint badges on discovery cards vs 059 Decision 1) are recorded as Open Questions in
**concern 064** and are not decided here.

**Build order (three sibling seams touch the same `screenMachineDetail` layout — pin them, do not
build in parallel):**

- **067 → 066.** Both render the same `data.groups` on `screenMachineDetail`: 067 (this spec,
  Decision 4) lays out the **flat** recipe grid and filters it by type/source; 066 **relocates** the
  discovery-pre-filled action rows out of that flat grid into per-context cards. 067 composes Screen C
  first; **066 lands after** and owns the merged discovery rendering — re-homing 067's type/source
  filter over the context cards it introduces (see [066](066-todo-context-grouped-discovery-ui.md)).
  Both edit the `data.groups` render path **and** the `app.css` breakpoints, so they must not build
  concurrently.
- **067 → 068.** 067 owns the SSH/connectivity **card placement** and ships it **without** the facts
  strip or handshake-latency/last-probed read-outs (the data does not exist yet); **068 lands after**
  and fills the facts strip + latency into 067's layout (see
  [068](068-todo-host-facts-connectivity.md)). 067 must ship first so 068 has a placement to fill.
- **069** is the optional run-history child; it swaps its server list in behind 067's Recent-runs
  section when/if it lands (see Known Gaps).

## Decision

`#/machines/<id>` (`ROUTES`, `app.js:3052`; the machine route at `:3056`) becomes the composed per-machine dashboard, built from
existing data and existing components:

1. **Per-machine tri-axis footprint + legend + Consumers grid, from the existing Monitor read.** The
   machine page fetches `GET /monitor?machineId=<mid>` — the spec-029 scoping built for exactly this,
   so only the one machine is assembled — and renders **the same** `axisMeter` ×3 (RAM/CPU/Disk) +
   `consumerLegend` + `computeOther` + the **Consumers `consumerCard` grid** that the fleet Monitor's
   `paint()` composes (`app.js:2048`–`:2058`), one machine, no lens/tag bars. That Consumers grid is
   part of the machine-page footprint section, and it is where 073's merged `:8080 · mgmt :8081` card
   renders for free (`consumerCard`, `app.js:2594`; management-port line `:2611`–`:2614`). Consumer
   axes are filled **one-shot on mount** by the same client `refresh()` (spec-024/037 — no server
   sampler), matching the fleet Monitor's default `Single` cadence (`{key:"single", ms:0}`,
   `app.js:1513`; `applyCadence` sets no interval for `ms:0`, `app.js:1882`); a **"Run now"** control
   re-polls on demand. There is **no standing footprint poll interval** on this page (see
   Implementation and Known Gaps). The heartbeat "updated Ns ago" ticker and the Recent-runs render
   that DO run are registered with the route-away cleanup (`runViewCleanup`, `app.js:3098`, invoked on
   dispatch at `:3116`). No new endpoint, no new meter component, no fork of the paint path.
2. **SSH / connectivity card.** A `.card` with a `kv` list from the already-fetched `MachineView`:
   status chip, `loginUser@host:port` (mono), plus the app key identity from `GET /ssh/public-key`
   (`fingerprint`, key type prefix parsed client-side from `publicKey`). The existing "Test
   connection" button moves into this card (same `POST /machines/<mid>/test` + chip-swap logic,
   `app.js:626`–`:640`). Mockup's "Handshake latency" and "last probed" rows need data that does not
   exist — **068** (which lands *after* this spec and fills them into this card); until then the card
   ships without those rows (omitted, not rendered as `—`).
3. **Recipes-with-state list.** The recipe groups keep the existing `actionsList`/`actionCard`
   spec-044 card grid (state chip + sudo badge + split-approve) — **not** the mockup's flat rows with
   a bare Run button, which would bypass the review-safety guard (`approvalSplit`'s
   review-before-approve rule stays). The mockup's per-row verb badge and command echo are the 053
   verb fork — deferred to concern 064.
4. **Search box + type/source filter chips over the recipe list.** A text input (name/description
   substring, case-insensitive) plus `chipBtn` chips (`app.js:1907`) for recipe **type** (the
   distinct `g.recipe.type` values present) and for **source** (native/docker) filter `data.groups`
   (`app.js:642`) **client-side** — pure re-render, no re-fetch, same posture as the Monitor lens
   toggles (spec-034 §7). The **source** dimension is unconditional: 063 is done, so
   `RecipeView.appPortList` is present and each `AppPortView.runtime` carries the source (`docker` ⇒
   docker, anything else ⇒ native — the server's own `sourceOf`, `MonitorDtos.java:298`). A group's
   source is derived from its `appPortList` runtimes. **The undefined case is specified here:** the
   majority of recipes (blueprint/custom) have an **empty** `appPortList` (`RecipeDtos.java:60`), and
   a discovery recipe may carry mixed runtimes. Rule — a group matches a source chip only when at
   least one `appPortList` item's runtime maps to that source; a group with an **empty `appPortList`
   matches no source chip** and shows under an **"other/none"** source (so turning on `native` or
   `docker` never silently hides the un-pre-filled majority). A mixed-runtime group matches every
   source its items carry.
5. **Recent runs, this-browser scope.** A "Recent runs" section renders `Runs.all().filter(r =>
   r.machineId === mid)` with the `#/runs` row idiom (`screenRuns`, `app.js:1248`–`:1254`) and the
   honest spec-005 caveat ("launched from this browser"). Server-backed history that survives the
   browser is **069** and swaps in behind this same section when/if it lands.

Layout: sections stack in mockup order (head → footprint → recipes → runs + SSH two-column grid),
collapsing to one column in the spec-043 phone block where `.app-cards`/`.action-cards`/`.host-panel`
already go single-column — `@media (max-width: 480px)`, `app.css:806`–`:836` (the coarser ≤720px
breakpoint is `app.css:133`, not the block cited here previously); the two-column runs/SSH grid is
new CSS but token-only, no new colours.

## Implementation

All work in `src/main/resources/static/{app.js,app.css}`. Build via `h()` only (spec-012 XSS
discipline — no `innerHTML`); **restart `spring-boot:run` for every edit** (user memory
`dev-server-static-skew-on-branch-switch`).

- **Fix the in-place re-mount leak first (this is a blocker, not a nicety).**
  `screenMachineDetail` is re-invoked **directly**, bypassing the router, from four call sites:
  `approvalSplit`'s `onDone` (`app.js:705`), the post-discovery re-render (`app.js:789`),
  `toggleFamily` (`app.js:810`), and the review drawer's `done()` (`app.js:951`). The router's
  teardown (`runViewCleanup`, `app.js:3098`, called from `route()` at `:3116`) runs **only on router
  dispatch**, so once this spec attaches a heartbeat/Recent-runs timer to the page, every in-place
  re-mount would (a) create a fresh timer set and **orphan the previous interval permanently** (until
  page reload), and (b) wipe item-4's search text + chip selections. The Monitor pattern
  (`currentViewCleanup = stopTimers`, `app.js:1876`) is safe **only** because `screenMonitor` is
  entered exclusively via `route()`; this page is not. The builder must therefore either **(a)** run
  any pending `currentViewCleanup` at `screenMachineDetail` entry before wiring new timers, **or**
  **(b)** convert those four in-place re-invocations to a **partial refresh** of just the affected
  section that preserves the live timers and the filter state. Do not add any page timer without
  closing this.

- **`screenMachineDetail` (`app.js:607`).** Add `api("GET", "/monitor?machineId=" +
  encodeURIComponent(mid))` to the existing `Promise.all` (machine + recipes + discovery). From the
  response take `machines[0]` (owner-scoped; absent ⇒ omit the footprint section). Reuse the Monitor
  route's section-building internals — but the extraction is **bigger than one closure**.
  `buildSection` (`app.js:1998`–`:2098`) closes over ~15 pieces of `screenMonitor`-local state that
  must move with it into the shared helper: the `models` consumer map (`app.js:1823`); the six
  denominator/usage maps `hostMemTotal`/`hostCores`/`hostDiskTotal`/`hostMemUsed`/`hostCpuUsed`/
  `hostDiskUsedPct` (`app.js:1809`–`:1816`); the lens/bucket toggles `lens`/`showDocker`/`showSystem`
  (`app.js:1806`–`:1807`); the filter predicates `selectedNamed`/`noAppsOn` (`app.js:1845`, `:1835`);
  and `toggleApp` (`app.js:1897`). The four host-vital poll helpers that `refresh()` calls —
  `pollHostTotal` (`app.js:2103`), `pollHostCpuUsed` (`:2119`), `pollHostCores` (`:2133`),
  `pollHostDiskTotal` (`:2152`) — live in `screenMonitor` scope **right after** `buildSection` and
  must move together. Three semantics to settle on the extracted helper for the single-machine page:
  - **`toggleApp` is dead here.** Its affordance is "Filter the fleet to <app>" (`consumerCard`,
    `app.js:2604`–`:2606`), meaningless on a one-machine page. Disable/remove the app-name toggle
    (pass a no-op, or render the consumer name as plain text).
  - **Lens/bucket toggles are dropped.** The page shows `lens="apps"` only — no Databases lens, no
    docker/system reveal (`showDocker`/`showSystem` stay `false`). State the consequence plainly:
    the machine page shows **strictly less** than the fleet Monitor does for that machine (no DB
    lens, no bucket reveal); the full breakdown stays on `#/monitor`.
  - **Strip `buildSection`'s duplicate identity head** (`app.js:2001`–`:2007`: `<h2>host</h2>` +
    `loginUser@host:port` + status chip) — 067's `pageHead` already carries exactly that
    (`app.js:658`–`:659`). The extracted helper renders body-only when the machine page calls it.

  Poll cadence: **one-shot on mount** — call `refresh()`→`paint()` once, plus a **"Run now"** button
  that re-calls `refresh()`; set **no poll interval** (matches the fleet Monitor's default `Single`,
  `{key:"single", ms:0}`, `app.js:1513`, whose `applyCadence` installs no timer for `ms:0`,
  `app.js:1882`). No cadence selector on this page.
- **SSH card.** New small render fn `sshCard(machine, pubkey)`; fetch `/ssh/public-key` lazily in
  the same `Promise.all`. Reuse `.card` + the `kv`/`dl` idiom (`app.js:894` region) + `chip()`
  (`app.js:197`) + `copyHostButton` (`app.js:434`). Move `testBtn` (existing logic unchanged) into
  the card footer.
- **Filter bar.** Above `groups`: an `<input>` (reuse the `.mono` input idiom, `app.js:1246`) and a
  `.filter-chips` row of `chipBtn`s (`app.js:1907`) from the distinct `g.recipe.type` values **plus**
  the two `native`/`docker` source chips. Filtering is a client-side re-render of the groups
  container: a group matches when its recipe name/description or any action name matches the query
  AND its type chip (if any type chips are on) is selected AND its source chip (if any source chips
  are on) is selected. **Source of a group** derives from its `RecipeView.appPortList` runtimes
  (`docker` ⇒ docker source, else native; `sourceOf`, `MonitorDtos.java:298`): a group matches a
  source chip when at least one item carries that source; a group with an **empty `appPortList`**
  (blueprint/custom — the majority, `RecipeDtos.java:60`) matches **no** source chip and lists under
  an **"other/none"** source when any source chip is active. Actions within a matched group are not
  sub-filtered (the card grid stays whole — predictable approve targets).
- **Recent runs.** `Runs.all().filter(...)` sliced to ~10, rendered with the `screenRuns` list rows;
  link each to `#/runs/<id>`. Empty state via `empty()` (`app.js:287`) with the this-browser caveat.
- **CSS.** One new grid rule for the runs/SSH two-column section, collapsing to one column in the
  spec-043 phone block alongside `.host-panel` (`app.css:827`) / `.action-cards` (`:811`) —
  `@media (max-width: 480px)`, `app.css:806`–`:836` (**not** the coarser ≤720px block at `:133`);
  everything else reuses `.card`, `.section`, `.list`, `.filter-chips`, `.legend*`, `.meter*`
  classes verbatim. No mockup skin (`.fp-*`, `.facts`, rounded chips) is ported — identity is **065**.
- **Tests/evidence.** No server change ⇒ no Java tests move. Live evidence per CLAUDE.md "UI
  evidence": a **bare** machine cannot show a filled footprint — the axis fills come from **APPROVED**
  monitor host-vital actions (`pollHostTotal` and siblings filter `approvalState === "APPROVED"`,
  `app.js:2105`), so a fresh machine renders `—` axes / "No discovered consumers" until those are
  approved. The evidence flow is therefore: register machine → **run discovery** (proposes the
  monitor recipes/host-vital actions) → **approve the monitor host-vital actions** → the footprint
  bars + legend fill; then verify the SSH card with fingerprint, the type/source filter chips
  narrowing the recipe cards, and (after one run) the recent-runs row.

## Known Gaps

- **Facts strip is 068 (lands after 067).** OS/kernel/uptime/cores/RAM/arch (mockup `:792`) need a
  facts probe + storage that do not exist; 068 lands **after** this spec and adds the strip into
  067's layout. Likewise handshake latency / last-probed on the SSH card.
- **Run history is browser-scoped.** `ca.runs` is a localStorage cache (`app.js:90`) — another
  browser sees nothing and eviction is at 50 entries. Server-backed history is **069 (optional)**;
  this spec deliberately ships the honest local slice first.
- **`GET /monitor` cost per machine-page visit (flag).** `?machineId=` scoping bounds assembly to
  one machine, but the read still walks the machine's full recipe/action tree on each visit. This
  page runs the footprint **one-shot on mount** plus explicit "Run now" — **no standing poll loop**
  (Decision 1) — so the per-visit cost is one assembly, not a repeating one. If profiling still shows
  `/monitor` hot on this route, the fix is a server-side slim view — flagged for 064, not solved here.
- **Verb badges, command echo, and declared apps** on recipe rows are concern-064 Open Questions
  (053/060 scope — the model has no verb field); rows render name + state chip + sudo only.
- **The footprint needs APPROVED monitor actions, not just a poll.** The axes are client-computed
  (no server sampler, spec-029 gap), and each fill comes from an **APPROVED** host-vital action
  (`pollHostTotal`/`pollHostCpuUsed`/`pollHostCores`/`pollHostDiskTotal` all filter
  `approvalState === "APPROVED"`, `app.js:2105`, `:2121`, `:2137`, `:2154`). A machine with no
  approved monitor actions shows `—` axes / "No discovered consumers" **indefinitely** — not "until
  the first poll cycle." Filled bars require register → discover → approve those actions (see the
  evidence flow); "Run now" only re-polls what is already approved.
- **069 will inherit this page's own poll runs.** Each footprint cycle issues real `POST /runs` per
  approved host-vital action. Today those do **not** pollute the Recent-runs list — `ca.runs` is
  written only at explicit launch time (`Runs.remember`, `app.js:1087`), never by the monitor
  polls. But when **069**'s server-backed history swaps in behind this same section, that history
  *will* be dominated by the machine page's own poll runs; 069 must filter them out (e.g. by `Via`
  / monitor-origin) — flagged for 069, see [069](069-todo-run-history-endpoint.md).

## Implementation Notes

Built on `moacyrricardo/bol-896-cpt-067-machine-dashboard-composition` (PR #98, base `main`),
front-end only in `src/main/resources/static/{app.js,app.css}` — no Java/server change, so the
Maven suite is unchanged (387 tests green) and the client logic is covered by a new headless
render-check.

**Change division (per `CONTRIBUTING.md`).** Five commits, refactor-first:
1. `todo→doing` (isolated rename + header flip).
2. **Enabling refactor** — extract the footprint section factory `makeFootprint(cfg)` out of
   `screenMonitor` (the `buildSection` closure, the four `pollHost*` helpers, the six host
   denominator/usage maps, and the lens/bucket state → `fp.view`). **Behaviour-neutral**: the
   fleet Monitor renders identically and all five pre-existing `src/test/js/*.render-check.js`
   still pass. This is why the behaviour commit reads as a pure delta.
3. **Behaviour** — recompose `screenMachineDetail` (footprint via the shared factory, SSH card,
   search + type/source filter, recent runs) + the in-place re-mount leak fix + CSS.
4. Test + a small behaviour-neutral lift of the source-classification predicates to module scope
   so they are unit-testable.
5. Eval fix — degrade a failed `/monitor` read to an omitted footprint.
`## API Modules` = **None**, so the API-Diff subsection is skipped (per `CONTRIBUTING.md` §9).

**How the build differed from / sharpened the spec.**
- **Leak fix chose option (a).** `screenMachineDetail` runs `runViewCleanup()` at entry (before
  wiring the heartbeat), which closes the timer-orphan blocker. Consequence, as the spec allows:
  the search text + chip selections are **not** preserved across the four in-place re-mounts
  (approve / discover / toggle-family / drawer-done); option (b) would have preserved them. This is
  spec-sanctioned ("either (a) … or (b)") and left as a possible later polish.
- **`makeFootprint` shape.** The factory owns the host maps + `pollHost*` and takes injected
  `models` / `selectedNamed` / `noAppsOn` / `onToggleApp` + a `showHead` flag, exposing `{ view,
  buildSection }`. The fleet route drives lens/bucket through `fp.view`; the machine page passes a
  fixed `apps` lens, `showHead:false` (its `pageHead` owns the identity + status chip), and a
  **null** `onToggleApp` so `consumerCard` renders the consumer name as **plain text** (the fleet
  "filter to app" affordance is meaningless on one machine). The machine page therefore shows
  strictly less than `#/monitor` (no DB lens, no bucket reveal) — by design.
- **Source chips are inert on this route today (recorded gap).** The spec's premise that
  `RecipeView.appPortList` carries the source assumes a populated list, but `GET /recipes?machineId=`
  maps via the plain `RecipeView::of` (empty `appPortList`), so **every** recipe here is
  "other / none" and the native/docker chips only re-label under that heading rather than narrowing.
  The spec's hardened empty-majority rule ("unconditional chips; empty ⇒ other/none, never hidden")
  is implemented exactly, so it **degrades correctly**; populating a parsed `appPortList` on the
  list path is a server change that belongs with **066** (which re-homes the discovery rows and
  owns the merged source rendering), not presentation-only 067.
- **Robustness.** Both the `/monitor` and `/ssh/public-key` reads `.catch(→null)` so a footprint or
  key-read error omits just that section/row rather than failing the whole dashboard.
- **Two "Copy host" buttons** (page head + SSH-card footer) — both spec-referenced; minor
  redundancy left as directed.
- **Testing.** New `src/test/js/machine-dashboard-composition.render-check.js` (the `node <file>`
  idiom, not wired into `mvn`) locks: `makeFootprint` head-strip / no-fork, `consumerCard`
  plain-name when toggle-less, and the source-filter empty/docker/native/mixed classification incl.
  the "other / none never hidden" rule.
