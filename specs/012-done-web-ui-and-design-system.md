# 012 — Web UI shell, design system & the approval screen

**Status:** done · **Branch:** `moacyrricardo/spec-012-web-ui-and-design-system` ·
**Linear:** blocked for this repo — tracked as `spec-012` (no issue identifier).

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
**Blueprints**, **Runs**, **MCP surface**, **Tokens**, **App key** — + a top bar
with the signed-in user and sign-out.
- **Login** — full-screen (no shell): a sign-in card with **Sign in with Google**
  (011) and, in the `dev` profile, an email **dev-bypass** field.
- **Machines** — list with connection-status chips + tags; **Register machine**.
- **Register machine + onboarding** — the connection form (host / port / login
  user / tags) shown alongside **the app public key to install** — the key + copy
  button + an `>> ~/.ssh/authorized_keys` snippet — then **Register & test
  connection** → status flips to `ONLINE` on success. Sourced from 003's
  `GET /api/ssh/public-key`.
- **App SSH key** — standalone view of the single app-owned public key +
  fingerprint + install snippet (one keypair for the whole fleet; the private key
  never leaves the box). Also surfaced inline during onboarding.
- **Machine detail** — its recipes → actions, each with an approval-state chip;
  "Discover recipes"; per-action row links to the approval screen; approved
  actions expose a **Run** affordance.
- **Approval screen (centerpiece)** — for one action: name + description; the
  **command rendered in mono** with `LITERAL` tokens plain and `PARAM` tokens
  visually distinct (accent underline) each showing its rule (`ALLOWED_SET` /
  `REGEX` / `INT_RANGE`); the **`sudo` flag as a prominent `--s-warn` badge**;
  provenance (who registered, when, blueprint source if any); **Approve / Revoke**
  (an approved action also shows **Run action**). If edited since approval, a
  **`--s-bad` "changed since approval — re-review" banner** (backed by the 004
  content-hash). Approve POSTs to `/api/actions/{id}/approve` (UI session only).
- **Run screen (parameter entry)** — reached from an approved action. A param form
  with a **widget per `ParamKind`**: `ALLOWED_SET` → dropdown, `REGEX` → text (the
  pattern shown as a hint), `INT_RANGE` → number (min/max). A **live resolved-
  command preview** fills each `PARAM` slot as values are chosen; the **Run button
  stays disabled until all params are set**, with client-side checks mirroring the
  server `ParamBinder` rule. Run → `run_action` via `RunService` (005).
- **Run view** — the resolved **"command that ran"** (param values bound in, not
  placeholders) + a **"parameters used"** panel + status chip + exit code + a mono
  terminal. Running runs **stream** over the 005 SSE endpoint (auto-scroll + pause);
  finished runs render at once; `FAILED` stderr is styled `--s-bad`.
- **Blueprints** — list; author (name + actions); **instantiate** onto selected
  machines or a tag → shows the per-machine pending actions created.
- **MCP surface** — read-only operator view of the `/mcp` tool catalog grouped
  **Read / Create (never approves) / Run / Bootstrap**, each tool with its
  signature + one-line description, a prominent **"there is no approve tool"**
  callout, and a connection card (endpoint, `Bearer <personal token>`, "acts as
  you", scope, resources). Renders from a small "describe tools/capabilities" read
  source; makes the trust model legible to the human.
- **Tokens & pairing** — list / create / revoke **personal MCP tokens**; **create
  reveals the plaintext once** in a modal ("copy now — shown once"). The **`/setup`
  pairing page** (011) shows the requesting client + `userCode` + expiry, an
  **Approve / Deny**, a short **"how an agent connects"** explainer, and issues the
  token via the same reveal-once modal on approval.

**Accessibility & polish.** WCAG AA contrast on both themes; visible keyboard focus
(`--accent` ring); state conveyed by label + icon, not color alone; honor
`prefers-reduced-motion` (no auto-scroll animation, no pulse). No inline styles —
everything through tokens/classes. All server-rendered text escaped via
`common/HtmlEscaper`; client-side rendering sets `textContent`, never `innerHTML`,
for any user/cloud-derived string (tags, names, output) to avoid XSS.

**Visual reference.** A clickable static **mock** of every screen/flow above (the
design tokens, the approval screen, the parameter-entry run form + live preview,
the run view, the MCP-surface page, login, the app-key onboarding, and the
token-reveal/pairing flow) was built and reviewed as the visual reference for this
build — treat it as the intended look and interaction.

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

## Implementation Notes

The build landed as specified: a framework-free static shell (`index.html`) plus
`tokens.css` (design tokens, light/dark via `prefers-color-scheme`), `app.css`
(components incl. the `chip--{state}` component), and `app.js` (hash router +
`fetch` + per-route render functions rendering from `/api` JSON). The
security-critical approval screen renders the resolved command in mono with
`LITERAL`/`PARAM` token distinction, the `sudo` `--s-warn` badge, provenance, and
the "changed since approval" guard; the run form carries a per-`ParamKind` widget
with a live resolved-command preview and a Run button gated until all params are
set. Client-side rendering uses `textContent` (never `innerHTML`) for
user/cloud-derived strings, honoring the spec's XSS mitigation.

**Change division.** No `CONTRIBUTING.md` in this repo, so there is no project
authority to assess the commit split against. For the record: the UI shipped
across `spec-012` commits on the branch (mark doing; web UI shell + design system
+ JSON-driven screens; approval-drift banner wiring + dead-code drop; SSE
exit-event render fix), with this closeout as the `doing → done` spec commit. Per
the repo's blocked-Linear rule, commits use `spec-012` subjects and the spec
carries no issue identifier.

No API Diff subsection: `CLAUDE.md` `## API Modules` is **None**, and this branch
is UI-only regardless.

### Deferred (new-arch follow-ups)

- **Real "Sign in with Google" is not actually wired in the UI.** The button only
  toggles a textarea where a user must manually paste a Google ID-token
  credential; there is no Google Identity Services integration (no GIS client
  script, no client-id-configured button that mints the credential). The
  dev-bypass path works fully and the backend `GoogleIdTokenServiceImpl` verifies
  real tokens, but the production Google login flow needs its own follow-up
  (external GIS script + `ca.auth.google-client-id` wiring). Consistent with S1'
  deferring non-local use.
- **MCP surface page (`screenMcp`) renders from a hardcoded `MCP_TOOLS` catalog in
  `app.js`** rather than the "small describe tools/capabilities read source" the
  spec calls for. No live capabilities endpoint exists yet, so this is a
  reasonable v1, but the static list can silently drift from the real `/mcp`
  surface as tools change; a later spec should expose and render a live
  tool/capability catalog.
- **Optional test-coverage:** no test asserts the new `changedSinceApproval` field
  flips true on drift (the branch adds zero tests). Low severity because the
  underlying condition is already covered at the service layer (`RunServiceTest`
  exercises `ActionModifiedException` for `APPROVED`+hash-mismatch), and the field
  is a thin re-derivation of that tested state.
