# 043 — Mobile-responsive UI (usable on cellphones)

**Status:** todo · no Linear issue (blocked for this repo; tracked as `spec-043`).

## Context

The web UI (spec-012) is a hand-rolled vanilla-JS shell — no CSS framework, `app.js`
builds the DOM through the `h()` helper (textContent-only, no `innerHTML`), styled by
`tokens.css` + `app.css`, theme-aware. It already ships a `<meta name="viewport">` and
**one** breakpoint — `@media (max-width: 720px)` (`app.css:122`) — that collapses the
sidebar grid to a single column and wraps the nav. That is the *only* responsive rule;
everything else is laid out for a desktop:

- **Nav** (`index.html`) is 7 horizontal links (Machines / Monitor / Blueprints / Runs /
  MCP surface / Tokens / App key). On a phone it wraps to 2–3 crowded rows.
- **Monitor** — the densest screen: the tri-axis segmented bars (`.host-panel`), the
  legend, `.filter-chips` (tag + app-name), the `.monitor-controls` row (cadence select,
  Run-now, counters), the `.app-cards` grid, and the consumer **drawer** are all sized
  for width.
- **`.app-cards`** is a multi-column grid; **`.row-between`** rows (page-heads, list
  items, action rows) put label + controls side by side; **`.list`** rows (recipes,
  actions, runs) and forms (register machine, approval review, the 042 command builder)
  assume desktop width.

Result: it *renders* on a phone but is cramped, controls are below comfortable touch
size, and some rows overflow horizontally.

## Decision

Make the UI **usable on phones (≈360–430 px) and tablets**, by **extending the existing
token + CSS system** — CSS-first (media queries + fluid layouts), no framework, no new
layout library. Keep the hard UI constraints: `h()` / textContent-only, theme-aware
(don't regress the dark/light tokens), and the approval gate/data model untouched (this
is presentation only). Adopt a **mobile-usable, not mobile-redesigned** bar: the same
information architecture and screens, reflowed to one column with touch-sized controls.

Concrete calls (the open UX choices resolved):

- **Breakpoints:** add named tokens and use two — **`--bp-sm: 480px`** (phone) and the
  existing **720px** (tablet/stacked-shell). Design mobile-first where practical.
- **Nav:** replace the wrapping row with a **collapsible menu** — a "Menu" toggle button
  (a small JS toggle on the existing shell, `aria-expanded`) that reveals the nav as a
  vertical list on ≤720px; the horizontal bar stays on wider screens. (Chosen over a
  bottom tab bar — 7 destinations is too many for tabs — and over leaving it wrapped.)
- **Dense views stack:** `.app-cards` → single column; `.row-between` → column (label
  above controls) at `--bp-sm`; the monitor `.monitor-controls` and `.filter-chips` wrap
  onto their own full-width rows; the consumer **drawer** becomes a **bottom sheet**
  (full-width, slides up) on ≤`--bp-sm` instead of a side panel.
- **Touch targets:** buttons, links, chips, and toggles get a **≥44 px** min height and
  larger tap padding on ≤`--bp-sm`.
- **No sideways scroll:** the page body never scrolls horizontally; genuinely wide
  content (the mono command preview, the segmented bars, any table-like list) lives in a
  container with `overflow-x: auto`.

## Implementation

All in `src/main/resources/static/` — `tokens.css`, `app.css`, `index.html`, and a
minimal `app.js` nav-toggle. No backend change.

- **`tokens.css`:** add `--bp-sm: 480px` (+ keep 720 as the tablet breakpoint); optionally
  a `--tap-min: 44px` token.
- **`index.html`:** add a "Menu" toggle button to the shell header, `hidden` above 720px
  via CSS; give `.nav` an `id` for the toggle to target.
- **`app.js`:** a small handler that toggles a class (e.g. `nav--open`) + `aria-expanded`
  on the toggle; collapse the menu on route change so tapping a link closes it. Stays
  textContent-only, uses `h()`; no `innerHTML`.
- **`app.css`:**
  - Fold the current `@media (max-width: 720px)` block into a coherent set: nav collapsed
    behind the toggle (vertical list when open), full-width `.view` padding reduced.
  - Add `@media (max-width: var-equivalent 480px)` rules: `.app-cards {grid-template-
    columns: 1fr}`; `.row-between {flex-direction: column; align-items: stretch}`;
    `.monitor-controls`/`.filter-chips` full-width wrap; `.list` item rows stack; forms
    (`.field`) full-width inputs; the drawer → bottom-sheet positioning.
  - Global touch sizing on small screens (`min-height: var(--tap-min)` for `.btn`,
    `.tag--filter`, nav links); ensure the segmented bars + mono command preview sit in
    `overflow-x: auto` wrappers.
  - Preserve both themes (rules go through the existing tokens; do not hard-code colours
    inside the new media queries).

## Known Gaps

- **No automated responsive test.** These are CSS media queries + a JS toggle; the repo's
  `src/test/js/*.render-check.js` checks DOM structure, not layout at a viewport width.
  Acceptance is a **manual checklist** (below); a headless viewport-screenshot harness is
  a possible later addition, out of scope here.
- **Mobile-usable, not a mobile redesign.** Same screens/IA; no phone-specific flows,
  gestures, or offline/PWA behaviour.
- **Landscape-phone and very small (<360 px) nuances** are best-effort, not guaranteed.
- The **MCP surface / Tokens / App key** pages are low-traffic on mobile; they get the
  baseline reflow but no bespoke treatment.

## Acceptance

At 360 / 390 / 430 px widths (and 768 px tablet), in both light and dark: no horizontal
body scroll on any screen; the nav is reachable via the Menu toggle and closes on
navigation; the Monitor bars, legend, filter chips, cadence controls, and consumer
drawer are all readable and operable; app-cards and list/row-between rows are single
column; every button/link/chip is comfortably tappable (≥44 px). Existing desktop layout
(>720 px) is unchanged.

## Related

- spec-012 (web UI shell & design system — the tokens/`h()` idiom this extends),
  spec-034 (the monitor bars/drawer — the densest surface), spec-042 (the command-builder
  form, which should be authored responsive from the start).
