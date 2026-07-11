# 012 — Web UI shell, design system & the approval screen

## Context

The web UI is the **human side of the gate** — it is where a person signs in,
reviews a pending action's exact command, and clicks *approve*. Everything the
backend specs build (machines, recipes/actions, runs, blueprints, pairing) assumes
a UI renders their JSON. Today the UI is only a placeholder (`static/index.html`
from spec 001). This spec locks the **design system** (tokens), the **JSON-driven
shell**, and the **approval screen** — the security-critical view whose clarity
*is* part of the gate: if a human can't clearly read what they're approving, the
gate is only as safe as a confusing screen allows.

No product/visual decision is left to the building agent: the palette, type,
spacing, component states, and screen contracts are all fixed here. (The palette
below is the proposed default — adjust via inline `#comment` if you have brand
colors.)

## Decision

- **No framework.** A **static** HTML + vanilla-JS shell served from
  `classpath:/static`, driven entirely by JSON from `/api`. No build step, no
  bundler, no SPA framework — matches ARCH's "static shell + JSON-driven vanilla
  JS."
- **Token-based design system** in CSS custom properties (one `tokens.css`),
  **theme-aware** (light + dark via `prefers-color-scheme`), utilitarian
  **control-room** aesthetic: a cool-slate neutral ground, **one** accent (teal),
  and **functional semantic colors** — separate from the accent — that encode
  state operators scan (approval state, run status, connection status).
- **Monospace for every command/param/output** — the thing being approved and run
  is a command, so it is always rendered in a mono face, never proportional.
- The **approval screen is the centerpiece** and renders the resolved command,
  its typed param schema, the `sudo` flag, provenance, and a **"changed since
  approval"** guard.

## Implementation

**Files** (`src/main/resources/static/`): `index.html` (shell + nav), `tokens.css`
(design tokens), `app.css` (components), `app.js` (hash router + `fetch` + render
functions). All assets inlined/static; the shell is the only HTML, everything else
is rendered from JSON.

**Design tokens (`tokens.css`)** — light / dark:

| Token | Light | Dark | Use |
|-------|-------|------|-----|
| `--ground` | `#f8fafc` | `#0b1220` | app background |
| `--surface` | `#ffffff` | `#111827` | cards, panels |
| `--surface-2` | `#f1f5f9` | `#1e293b` | insets, code blocks |
| `--border` | `#e2e8f0` | `#334155` | hairlines |
| `--text` | `#0f172a` | `#e2e8f0` | primary text |
| `--text-dim` | `#475569` | `#94a3b8` | secondary |
| `--text-faint` | `#94a3b8` | `#64748b` | disabled / neutral state |
| `--accent` | `#0891b2` | `#22d3ee` | links, primary buttons, focus |

**Functional semantic colors** (distinct from `--accent`; light / dark). One scale,
reused across the three state kinds:

| Meaning | Token | Light | Dark | Applies to |
|---------|-------|-------|------|-----------|
| good | `--s-ok` | `#16a34a` | `#4ade80` | `APPROVED`, run `DONE`, `ONLINE` |
| attention | `--s-warn` | `#d97706` | `#fbbf24` | `PENDING_APPROVAL`, `sudo` badge |
| bad | `--s-bad` | `#dc2626` | `#f87171` | `REVOKED`, run `FAILED`, `UNREACHABLE` |
| active | `--s-info` | `#2563eb` | `#60a5fa` | run `RUNNING`/`QUEUED` |
| neutral | `--s-neutral` | `#94a3b8` | `#64748b` | `DRAFT`, `UNKNOWN`, `OFFLINE` |

**Typography.** `--font-sans: system-ui, -apple-system, "Segoe UI", Roboto,
sans-serif` (chrome/labels); `--font-mono: ui-monospace, "SF Mono", Menlo,
Consolas, monospace` (commands, argv, params, run output). Type scale
12 / 14 / 16 / 20 / 28 px, weights 400 / 600. **Spacing** 4px base (4/8/12/16/24/32);
**radius** 6px (4px small); one subtle elevation for cards.

**State → chip.** A single `<span class="chip chip--{state}">` component colored by
the semantic tokens; every approval state, run status, and connection status renders
as one so state reads at a glance (color **plus** label — never color alone, for
accessibility).

**Shell & routes** (hash-routed, one render fn each): sidebar nav — **Machines**,
**Blueprints**, **Runs**, **Tokens** — + a top bar with the signed-in user and
sign-out.
- **Machines** — list with connection-status chips + tags; register form.
- **Machine detail** — its recipes → actions, each with an approval-state chip;
  "Discover recipes" action; per-action row links to the approval screen / run.
- **Approval screen (centerpiece)** — for one action: name + description; the
  **command rendered in mono** with `LITERAL` tokens plain and `PARAM` tokens
  visually distinct (accent underline) each showing its rule (`ALLOWED_SET` values
  / `REGEX` / `INT_RANGE`); the **`sudo` flag as a prominent `--s-warn` badge**;
  provenance (who registered, when, blueprint source if any); and **Approve /
  Revoke** buttons. If the action was edited since approval, a **`--s-bad`
  "changed since approval — re-review" banner** (backed by the 004 content-hash).
  Approve POSTs to `/api/actions/{id}/approve` (UI session only).
- **Run view** — status chip + exit code + a mono "terminal" panel streaming
  stdout/stderr live over the 005 SSE endpoint; auto-scroll with a pause toggle.
- **Blueprints** — list; author (name + actions); **instantiate** onto selected
  machines or a tag → shows the per-machine pending actions created.
- **Auth** — Google sign-in landing; the **`/setup` pairing page** (011): shows the
  requesting device's `userCode` and **Approve / Deny** for the MCP client.

**Accessibility & polish.** WCAG AA contrast on both themes; visible keyboard focus
(`--accent` ring); state conveyed by label + icon, not color alone; honor
`prefers-reduced-motion` (no auto-scroll animation, no pulse). No inline styles —
everything through tokens/classes. All server-rendered text escaped via
`common/HtmlEscaper`; client-side rendering sets `textContent`, never `innerHTML`,
for any user/cloud-derived string (tags, names, output) to avoid XSS.

**Tests / verification.** JS has no unit runner (matches birthday-rsvp); verify the
shell + approval screen + live run output in a real browser via `/spec-test-live`
(the durable Selenium/geckodriver tooling). Server-side, the static resources are
served under `/` and each screen's JSON contract is already covered by the backend
`*WebTest`s.

## Known Gaps

- **Utilitarian, single-purpose UI** — no component library, no dashboards/charts
  in v1 (a fleet-status or run-history dashboard would be a later spec, and would
  pull in the dataviz palette discipline then).
- **Palette is the proposed default.** If you want specific brand colors, adjust the
  token table; the *structure* (neutral ground + one accent + functional semantic
  scale, semantic ≠ accent) should hold regardless.
- **Client-side XSS surface** — free-form tags and command **output** are
  attacker-influenceable (cloud tags, remote stdout); the `textContent`-only rule
  is the mitigation and must be honored by every render fn.
- Builds after the backend it renders (needs the machine/recipe/run/blueprint APIs);
  independent of the backend fan-out otherwise.
