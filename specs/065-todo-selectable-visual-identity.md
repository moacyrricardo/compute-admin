# 065 — Selectable visual identity

**Status:** todo · Linear [BOL-894](https://linear.app/iskeru/issue/BOL-894) · build branch `moacyrricardo/bol-894-cpt-065-selectable-visual-identity` · **Part of concern 064 (mockup delivery).**

## Context

The 054 mockup (`specs/054-assets/mockup-compute-admin.html`) demonstrates the same product
surface under **three complete design identities** — Current (the spec-012 clone), Iskeru
(dark/gold, derived from the iskeru brand), Blueprint (light drafting-paper, invented) — switched
live by a `data-identity` attribute (`mockup:443`, switcher JS `:988–995`) over three full token
sets (`mockup:67–115`). Every component below reads `var()`s; an identity is a **product skin, not
a light/dark toggle** (`mockup:60–63`).

The live UI is already token-driven and one skin away from this: `tokens.css` declares the whole
palette/type/shape vocabulary on `:root` (`tokens.css:15–85`) and app.css components consume it.
But two structural facts block a drop-in port:

- **There is no `data-theme` anywhere.** Light/dark is pure `@media (prefers-color-scheme: dark)`
  (`tokens.css:87–115`). An identity that commits to one mode (Iskeru = always dark, Blueprint =
  always light) must **gate** that media block, or the OS setting fights the identity.
- **Four spots bypass the tokens.** `.btn--primary` hard-codes `color:#ffffff` (`app.css:275–279`)
  plus a dark-mode override `color:#06121a` (`app.css:280–282`); `.terminal` hard-codes
  `background:#06121a; color:#d5e3ea` (`app.css:385–386`); the modal and drawer backdrops hard-code
  slate scrims `rgba(15,23,42,.55)` / `rgba(15,23,42,.45)` (`app.css:451`, `app.css:645`). The
  button one is load-bearing: Iskeru's accent is gold `#d9a441` with dark ink `#17130a`
  (`mockup:86`) — on an OS-light machine the un-gated rules render **white-on-gold** primary
  buttons.

The mockup also gives each identity its own **shell personality** over the same logical regions
the app already has (`brand / topbar / nav / view`, `app.css:52–60`, `index.html:17–46`): Current
keeps the 240px left rail (`mockup:143–153`), Iskeru is a glassy sticky **top-nav**
(`grid-template-areas:"brand nav topbar" "view view view"`, `mockup:156–181`), Blueprint keeps the
rail but adds grid-paper ground, a mono uppercase nav, and a drafting **title-block**
(`mockup:184–212`) — the mockup's only net-new DOM element (`.pf-titleblock`, hidden by default at
`mockup:140`).

This spec is **presentation-only**: no endpoint, no DTO, no migration. Sibling specs (066/067)
build screens *on top of* whatever identity is active; they do not depend on this one.

## Decision

1. **A per-viewer design-system selector.** `data-identity` on `<html>` with three values —
   `current` (default) · `iskeru` · `blueprint` — each a complete token set ported from
   `mockup:67–115` into `tokens.css`. Per-viewer, client-side only: persisted as
   `localStorage["ca.identity"]`, following the existing `ca.jwt`/`ca.user`/`ca.runs` idiom
   (`app.js:70–98`). No server preference, no per-account setting.
2. **Identity gates the OS light/dark media.** `current` keeps today's viewer-theme-aware pair
   (light `:root` + dark media block). `iskeru` is a **committed dark** theme; `blueprint` a
   **committed light** one — for those two the `prefers-color-scheme:dark` block must not apply.
   The dark block at `tokens.css:87` is re-scoped to `current` only.
3. **The four hard-coded spots become tokens first** (the enabling fix), then identity-scoped
   shell CSS re-skins the existing regions. Blueprint's title-block is the **only** net-new DOM.
4. **Switcher lives in the product topbar** (`index.html:27–33`), next to `.userbox` — a
   first-class product control, unlike the mockup's scaffolding shellbar (which is not ported).
5. **Design forks stay in concern 064.** Fonts (self-hosted woffs vs system stacks) and the
   per-identity ≤720px answer are 064 Open Questions; this spec ships with system stacks and the
   fallback below.

## Implementation

### Token contract (tokens.css)

New tokens, added to the base `:root` so `current` needs no other change (values from
`mockup:67–81`):

| token | current (light) | purpose |
|---|---|---|
| `--accent-2` | `#0891b2` (= `--accent`) | second accent for gradients/duotones (`mockup:70,86,104`) |
| `--accent-ink` | `#ffffff` | text **on** the accent — the `.btn--primary` fix |
| `--accent-grad` | `var(--accent)` | primary-button/brand fill; a real gradient only in iskeru (`mockup:87`) |
| `--accent-soft` | `color-mix(in srgb, var(--accent) 13%, transparent)` | active-nav / soft accent wash (`mockup:88,178`) |
| `--font-display` | `var(--font-sans)` | headings/brand face (`mockup:76,94,110`) |
| `--btn-primary-shadow` | `none` | iskeru's gold glow (`mockup:97`) |
| `--hair` | `1px` | hairline border width (declared per-identity in `mockup:80/98/114`; contract token so blueprint can stay hairline if radius/borders ever thicken elsewhere) |
| `--terminal-bg` / `--terminal-ink` | `#06121a` / `#d5e3ea` | tokenizes `app.css:385–386` |
| `--backdrop-modal` / `--backdrop-drawer` | `rgba(15,23,42,.55)` / `rgba(15,23,42,.45)` | tokenizes `app.css:451`, `app.css:645` |

Then two override blocks, verbatim ports of `mockup:83–99` and `mockup:101–115` (palette,
semantics, categorical `--c-*`, fonts, `--radius:16px/10px` vs `2px/2px`, elevations,
`--btn-primary-shadow`), scoped:

```css
:root[data-identity="iskeru"]    { color-scheme: dark;  /* mockup:84–98 */ }
:root[data-identity="blueprint"] { color-scheme: light; /* mockup:102–114 */ }
```

`color-scheme` per identity replaces the base `light dark` (`tokens.css:16`) so native form
controls and scrollbars commit with the skin.

**Gating the dark block.** `tokens.css:87`'s `@media (prefers-color-scheme: dark) { :root {…} }`
becomes `:root:not([data-identity="iskeru"]):not([data-identity="blueprint"]) {…}` inside the same
media query — `current` (and a pre-JS missing attribute) keeps today's dark exactly; committed
identities ignore the OS. The current-dark block additionally sets `--accent-ink:#06121a` and
`--terminal-*`/`--backdrop-*` stay inherited (they are mode-independent today).

### The load-bearing fix (app.css)

- `app.css:278` `color:#ffffff` → `color:var(--accent-ink)`; add
  `background:var(--accent-grad); box-shadow:var(--btn-primary-shadow)`
  (`mockup:274–279` is the reference). **Delete** the `@media` override at `app.css:280–282` — it
  moves into the gated dark token block above. Without this, iskeru on an OS-light machine renders
  white-on-gold primaries.
- `app.css:385–386` → `background:var(--terminal-bg); color:var(--terminal-ink)`.
- `app.css:451` → `background:var(--backdrop-modal)`; `app.css:645` →
  `background:var(--backdrop-drawer)`. Iskeru overrides both to a black-based scrim
  (slate-tinted rgba over `#0b0d12` reads muddy).

### Identity-scoped shell CSS (app.css)

The app's shell already exposes the mockup's regions as grid areas
(`"brand topbar" / "nav view"`, `app.css:52–60`); identities re-skin by re-mapping, scoped
`:root[data-identity="…"] .shell …`:

- **iskeru** (`mockup:156–181`): `grid-template-columns:auto 1fr auto;
  grid-template-areas:"brand nav topbar" "view view view"`; brand/nav/topbar sticky, glassy
  (`rgba(11,13,18,.72)` + `backdrop-filter:blur`, `mockup:170`); `.nav` horizontal, pill links,
  active = `--accent-soft`+`--accent` (`mockup:176–178`); `.shell` gains the fixed radial ambient
  glows (`mockup:160–165`); `.view` centered `max-width:1080px`.
- **blueprint** (`mockup:184–212`): keeps the rail layout; `.shell` gets the 22px grid-paper
  `background-image` (`mockup:188–191`); `.brand`/`.nav a` go mono-uppercase-letterspaced
  (`mockup:195,201–204`), active link = inset accent bar, no fill (`mockup:203–204`); cards get the
  corner-tick `::before/::after` registration marks (`mockup:238–243`).
- **`.pf-titleblock`** — one net-new element appended inside `#view`'s parent in `index.html`
  (static, three `k/v` cells: project / machine-or-route / date idiom per `mockup:206–212`),
  `display:none` except under blueprint (`mockup:140`). No JS beyond filling the route cell.
- Per-component identity accents (chip/tag/input radius swaps, iskeru gradient `.btn--primary`
  hover lift) port from the `body[data-identity=…]` component rules (`mockup:235–292`) as
  needed — smallest set that makes each skin read true; not a pixel clone.

### Wiring (index.html + app.js)

- **Pre-paint stamp:** a 3-line inline `<script>` in `<head>` *before* the stylesheet links
  (`index.html:8–9`): read `localStorage["ca.identity"]` (try/catch, default `current`, whitelist
  the three values) and set `document.documentElement.dataset.identity`. Placing it on `<html>`
  before CSS loads means no flash-of-wrong-identity; the mockup's `body`-attribute placement
  (`mockup:443`) is not copied.
- **Switcher:** a compact segmented control (three buttons, mockup `seg` idiom `mockup:449–453`)
  in the topbar `.userbox` row (`index.html:28–32`). On click: whitelist-validate, write
  `localStorage["ca.identity"]`, restamp `dataset.identity` — tokens flip live, no reload. Also
  reachable pre-login: the login screen (`#login-root`, `index.html:13`) inherits the identity via
  `:root` tokens automatically; the switcher itself is topbar-only (acceptable — see Gaps).
- Restart the dev server to see any of this (`spring-boot:run` serves the startup
  `target/classes` copy — house rule).

### Tests / verification

No JS unit surface worth mocking; verification is the live-capture route: one screenshot per
identity of the machines list + one modal open (backdrop token) + one primary button per identity
under **both** OS modes (4 combos for `current`, iskeru/blueprint proven OS-invariant). The
white-on-gold regression is the explicit check: iskeru + OS-light + `.btn--primary` must render
dark-ink-on-gold.

## Known Gaps

- **Per-identity ≤720px is deferred to concern 064.** Spec-043's stacked shell
  (`app.css:130–136`, `nav-toggle` `index.html:23–24`) assumes a side rail collapsing to stacked
  rows; iskeru's horizontal top-nav has no mobile answer in the mockup. Until 064 resolves it,
  iskeru **falls back to the current stacked shell below 720px** (the identity-scoped grid remap
  applies only above the breakpoint) — visually conservative, never broken.
- **Fonts ship as system stacks.** The mockup approximates Inter / Space Grotesk with `system-ui`
  weights (`mockup:92–94`, noted at `mockup:952`); self-hosting woffs is a 064 Open Question.
  `--font-display` keeps the seam so a font drop-in is token-only later.
- **No server-side preference.** `localStorage["ca.identity"]` only, per the `ca.*` idiom
  (`app.js:70–98`): identity does not roam across browsers/devices and resets on cleared storage.
  A future account-level setting would need a user-prefs endpoint — out of scope.
- **Pre-login switching.** The login screen renders in the stored identity but hosts no switcher;
  a first-time viewer sees `current` until they sign in and switch. Deliberate — the login card
  stays minimal.
- **Mockup component rules are ported selectively.** Only the shell + the component accents listed
  above; screen-level composition (discovery cards, dashboard) belongs to 066/067, and the
  footprint/verb/app-badge forks those screens raise are 064's, not this spec's.
