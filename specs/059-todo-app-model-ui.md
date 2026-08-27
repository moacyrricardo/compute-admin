# 059 — App-model UI (new surfaces)

**Status:** todo · no branch yet · no Linear (blocked for this repo; tracked as `spec-059`).

## Context

Concern [054](054-concern-lightweight-app-model.md) settled *what an app is* (the mechanical
**{app-script, script-folder, context}** triple) and locked five decisions (2026-08-27); spec-055
fixed the record schema and the `ContextMapper` resolution seam; 056 produces the discovery sweeps
(ports + docker + non-listening apps) that resolve to contexts, and 057 the per-context disk/RAM/CPU
probes that feed spec-041's `computeOther`. This spec is the **presentation layer** of the 055–060
epic: it builds the three new web surfaces that render what 055–057 produce, entirely inside the
existing spec-012 vanilla-JS design system. Nothing here changes data, the approval gate, or the MCP
surface — it draws the model the siblings compute.

The UI today is a framework-free, JSON-driven single page (spec-012): one IIFE in
`src/main/resources/static/app.js` (3086 lines), styled by `static/app.css` + `static/tokens.css`,
routed by the hash router (`app.js` `ROUTES` ~:2899). Discovery already lives inside
`screenMachineDetail` as `discoverySection` (`app.js` ~:753) with per-family `.filter-chips`
toggles; the fleet footprint dashboard already renders tri-axis segmented bars and a labelled legend
(`screenMonitor` ~:1776, `axisMeter` ~:2407, `consumerLegend` ~:2442, `computeOther` ~:2385,
spec-034/041); add-machine already exists as `screenRegisterMachine` (~:543). **All three surfaces
this spec touches already exist** — 059 extends them to carry the 054 context model, reusing the
`h()` DOM constructor (`app.js:23`), the reusable card/drawer/meter/chip components, and the spec-043
responsive shell / spec-044 approval-drawer idioms verbatim. This spec **depends on** 055 (record
fields), 056 (context-grouped records + source notes), and 057 (per-context axis numerators); it
**feeds** the operator directly. It is presentation only — it consumes no new gate path.

> **Design reference:** [`054-assets/mockup-compute-admin.html`](054-assets/mockup-compute-admin.html)
> is the layout/content reference for the three surfaces (discovery grouped by context; the footprint
> bars; register-a-machine). Its rounded "iskeru" visual identity is **the current identity** for a
> separate visual-identity concern and is **out of scope here** (see Known Gaps) — 059 renders these
> surfaces in the *existing* spec-012 cool-slate + teal system (`tokens.css`), reusing current
> component classes, not the mockup's bespoke `.dbadge`/rounded-chip skin.

## Decision

Presentation is decided as follows (restating the 054 decisions that surface visually; the model
itself is fixed by 055–057 and is not reopened here):

1. **Discovery is grouped by context, not by family.** Discovery renders one **context card** per
   resolved context (055's `contextDisplay`), each showing the logical display path, a **source
   note** (how it was discovered — "app folder · discovered via port :8080 + systemd unit",
   "compose project · discovered via docker", "declared app · cron-launched · no port"), the
   **sibling-script list** (055 dec. 4 — every lifecycle script the wrapper rule collapsed to this
   context). Per-context **disk/RAM/CPU footprint is not shown on discovery cards** — those numbers
   are a product of the monitor poll loop (Surface 2), not of the one-shot discovery SSH sweep, so
   the discovery card carries identity and structure only and defers live footprint to the Monitor
   route. Non-listening apps (054 D4) appear as ordinary context cards with an empty port list,
   labelled as such. The
   per-family `.filter-chips` toggles (family enablement) **stay** as a filter above the grouped
   list — grouping and filtering coexist.

2. **Each discovered action carries an approve-to-add affordance.** Within a context card, each
   proposed action renders its verb/label + argv summary and a single **"Approve → add recipe"**
   control. That control invokes the **existing REST approval flow only** — it is the UI approval
   path, never a new endpoint and never an MCP call. The gate stays singular and UI-only (ARCH gate
   point 2, `GateArchTest`); this button is a convenience entry into `screenApproval`
   (`app.js` ~:957) / the approval REST call it already makes.

3. **The footprint dashboard groups consumers by context and fills the native disk axis.** The
   tri-axis segmented bars (RAM/CPU/Disk) + labelled legend of spec-034/041 are reused unchanged in
   structure; 057 makes a **native context** a first-class consumer with a real **disk** value (the
   axis that was `native — n/a` today, `app.js` ~:2464). Because `computeOther`'s `attr()` blindly
   sums `c[axis]` over rendered consumers (`app.js` ~:2386–:2391), a newly-non-null native disk %
   is subtracted from OTHER automatically — **no `computeOther` edit** (057 owns the numerators and
   the single-denominator guarantee; 059 only renders them). Colour is never the sole signal: every
   segment has a legend chip with its label (WCAG AA house rule, `consumerLegend`).

4. **Paths shown in the UI are the logical display path; the UI never surfaces the internal key.**
   The UI shows 055's `contextDisplay` (logical/promoted path) and script basenames. The internal
   dedup/pin `contextKey` (S9-secret per 055) is not rendered and is not needed client-side. Because
   these surfaces are `/api` (UI, `via=UI`) and not MCP, S9 is not in play on the wire here — but the
   UI still displays only what 055 exposes as display data, never a raw physical `*/releases/<ts>/…`
   path as identity.

5. **Add-machine is the existing two-card flow, reconciled with the context model.** Register keeps
   its spec-012 shape (key-authorize card + connection form); the only 054-driven change is a
   post-registration hand-off into the context-grouped discovery surface (decision 1) rather than the
   family-flat list.

## Implementation

All work is in the three static files — `src/main/resources/static/{app.js,app.css}` (and, if a new
top-level route is added, one `<nav>`/route entry) — plus reading the DTOs 055–057 already extend.
No server-side change, no migration, no MCP tool, no gate change. Build everything through `h()`
(`app.js:23`); never assign `innerHTML` (spec-012 XSS discipline — user/host-derived strings reach
the DOM only via `props.text`/text children). **Restart `spring-boot:run` for every front-end edit**
— `app.js`/`app.css` are served from the `target/classes` copy made at startup, not live from the
tree (project CLAUDE.md "UI evidence"; user memory `dev-server-static-skew-on-branch-switch`). A
browser reload alone shows stale UI; kill and restart to verify.

### Surface 1 — Discovery grouped by context

Extend `discoverySection(p, mid, families)` (`app.js` ~:753), which today is a `.section` of
per-family `.filter-chips` + a "Discover recipes" button (POST `/machines/<mid>/discover`), fed by
`GET /machines/<id>/discovery`. Keep the family-toggle bar (family enablement is unchanged), and
render **below it** a context-grouped list built from the discovery result 056 produces:

- **Data.** Consume the context-grouped records 055/056 expose on the discovery proposal channel —
  `contextDisplay`, `scriptFolder`, the sibling-script list, the source note, and the port list
  (whatever fields 055/056 land on the `app_port_list`/proposal JSON that
  `GET /machines/<id>/discovery` or the discover POST returns). Per-context footprint is **not** on
  this channel — it is a monitor-poll product (Surface 2), so it does not appear on discovery cards.
  059 **only reads** these; it does not define them (055/056 own the shape). **Action rows are sourced
  separately, from the recipes channel — not the discovery channel:** the proposed recipes/actions
  (carrying `rid`/`aid` and `approvalState:PENDING_APPROVAL`) already arrive on the existing recipe
  channel (`data.groups`, rendered today by the recipe list), whereas `GET /machines/<id>/discovery`
  returns only `{families}`. Surface 1 **re-groups `data.groups` by the 055/056 context fields** to
  attach each action row to its context card — no new endpoint, and no action payload added to the
  discovery channel.
- **Context card.** One card per context, reusing `.card` + `.section` scaffolding. Header: the
  logical path via a mono label (reuse the mono/`.crumbs` idiom, `app.js` `crumbs` ~:276) and the
  source note as a dim sub-line (`.page-head .sub` styling). **No footprint badge row here** —
  disk/RAM/CPU are monitor-poll numbers rendered on Surface 2 (the Monitor route), not on the
  one-shot discovery card, so this card links to Monitor for live footprint rather than restating
  numbers the discovery sweep never produced.
- **Sibling scripts + actions.** List the context's app-scripts (055 dec. 4) by basename. For each
  proposed action, render a row with the verb/label chip (`chip(state)`, `app.js` ~:197 — proposed
  actions are `PENDING_APPROVAL` → `warn`), an argv/command summary, and the **"Approve → add
  recipe"** control (decision 2). The control routes to the existing approval screen
  (`#/machines/<mid>/recipes/<rid>/actions/<aid>`, `screenApproval` ~:957) or fires the same approval
  REST call that screen uses — **no new endpoint**. After approval the recipe appears in the machine's
  normal recipe list (the existing reconciliation, `DiscoveryService.persist`); nothing here bypasses
  `screenApproval`'s confirmation.
- **Family filter coexists.** The family toggle bar stays as a filter above the grouped cards
  (`toggleFamily` PUT `/machines/<mid>/discovery/<key>` unchanged). A context card is shown when its
  discovering family is enabled.

### Surface 2 — Consumer-footprint dashboard (extends spec-034/041)

Reuse the fleet-monitor rendering path unchanged in structure; 059 only makes native contexts render
as consumers with a filled disk axis and groups by context:

- **Consumer model.** The client consumer object (assembled ~`app.js:2349`) gains no new *shape* —
  057 populates `c.disk` for native contexts (today left null, rendered `native — n/a` at
  `app.js` ~:2464–:2465). 059 renders whatever 057 sets: `axisMeter(label, consumers, axis, onOpen)`
  (`app.js` ~:2407) already draws one `.axis-seg` per consumer plus the hatched `.axis-seg--free`
  remainder; a non-null native `c.disk` simply draws its segment.
- **`computeOther` untouched.** `computeOther(machineId, hostUsed, named)` (`app.js` ~:2385) and its
  `attr()`/`seg()` math (~:2386–:2392) are **not edited** — a native disk % that 057 computed against
  the root-FS denominator (`denom.diskBytes`, the same `df -h /` total `hostUsed.disk` uses) is
  subtracted from OTHER by construction. 059 must **not** introduce a native disk % on any other
  denominator; that is 057's single-denominator invariant, and 059 depends on it, it does not
  re-implement it.
- **Legend + labels.** `consumerLegend(consumers, onOpen)` (`app.js` ~:2442) renders the mandatory
  labelled legend (`.legend`/`.legend-chip`/`.legend-dot`) so colour is never the only signal;
  categorical hue via `consumerColorVar(c)` / `--c-1..5` + `--c-docker`/`--c-system`
  (`app.js` ~:2328). The per-consumer drawer (`openConsumerDrawer`, `app.js` ~:2810, spec-044) shows
  the context's script list and footprint detail; reuse it, don't fork it.
- **Context grouping.** Where 057 attributes a footprint to a `contextKey`, the dashboard groups the
  consumer meters/legend by context (one legend chip per context). This is a rendering regroup over
  the existing consumer list — no server aggregation (spec-040 thin-BE posture; `MonitorService`
  stays a pure read-aggregate, host-relative axes still filled client-side).

### Surface 3 — Add-machine

Keep `screenRegisterMachine` (`app.js` ~:543) as the two-`.card` flow (public-key authorize card +
name/host/port/loginUser form → POST `/machines` → POST `/machines/<id>/test`). The only change is
the landing after a successful test: hand off to the context-grouped discovery surface (Surface 1)
on the machine-detail screen, rather than the family-flat list. Reuse `.card`, `.field`, `crumbs`,
`pageHead`, and the `mount(loading())` + `mountAsync` submit pattern unchanged.

### Styling (design-system reuse, not a reskin)

Every rule goes through existing tokens (`tokens.css`) and existing component classes
(`app.css`): `.card`, `.section`, `.filter-chips`/`.tag--filter`, `.tag`, `.chip--{ok,warn,bad,info,neutral}`,
`.meter`/`.axis-track`/`.axis-seg`/`.axis-seg--free`, `.legend*`, `.drawer`/`.drawer-backdrop`,
`.app-cards`/`.app-card`, the spec-043 tablet breakpoint (`.shell` collapse ≤720px, `.nav-toggle`)
and the ≤480px bottom-sheet drawer. New CSS is limited to context-card layout (path/source-note/
badge-row/script-list); it must define no new colour outside the token set and add no inline styles
beyond the dynamically-sized segment widths the monitor already uses. The mockup's `.dbadge`,
rounded `body[data-identity="iskeru"]` chips, and bespoke `.ctx-*` skin are **not** ported — they
belong to the deferred visual-identity concern.

### MCP surface / gate / S9

**No delta.** These are `/api` (UI) surfaces; no MCP tool is added or changed, no `ApprovalService`
or `*Repository` reference enters `mcp/`, and `GateArchTest` / the new 055 no-raw-path arch test stay
green untouched. The approve-to-add control (Surface 1) calls the existing UI approval path only — it
cannot approve anything the gate would not (ARCH gate points 1–4). Paths shown are 055's logical
display strings, rendered via `textContent`; the internal `contextKey` is never fetched or shown.

## Known Gaps

- **The visual-identity reskin is out of scope.** The mockup's rounded "iskeru" identity is the
  *current* identity for a separate concern; 059 renders strictly in the existing spec-012 slate/teal
  system. A full identity rewrite (new tokens, rounded chips, the `.dbadge`/`.ctx-*` skin) is that
  concern's work, not this spec's.
- **The record/mapping model is 055**, the discovery sweeps + source notes are **056**, and the
  per-context disk/RAM/CPU numerators + the single-denominator `computeOther` integration are **057**.
  059 renders their output and defines none of it; if a field this spec draws is absent, the fix lands
  in the owning sibling, not here.
- **Standalone (non-docker) DB sizing is 058** (054 D5's deferred spec) — a standalone-DB context
  card shows whatever 058 provides; until 058 lands, its size badges render `—`.
- **The verb & command contract (spec-053's surviving half) is untouched.** The approve-to-add rows
  render the verb/label 056 proposes; verb-level card controls, the closed vocabulary, and verb-level
  MCP tools are 053/060 scope. 059 draws the label; it does not model run semantics.
- **The on-demand "re-probe with sudo" affordance (054 D3)** is not built here — degrade-and-label
  labelling is 056's and the sudo-upgrade control is 057's. If 057 exposes a confidence flag on a footprint number,
  059 renders it as a badge/label, but the probe path and the sudo action are not 059's.
- **No new top-level nav surface is assumed.** Discovery-by-context extends the existing
  machine-detail discovery section and the footprint dashboard extends the existing Monitor route; a
  standalone nav entry is added only if review calls for it (one `<nav>` link + one `ROUTES` entry +
  one `screenX` fn, per the router contract).
