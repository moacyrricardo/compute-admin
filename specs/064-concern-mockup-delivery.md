# 064 — Mockup delivery experience

**Status:** concern · Linear [BOL-893](https://linear.app/iskeru/issue/BOL-893) · **umbrella for the mockup-delivery children (063, 065-069).**

> **Design reference:** [`054-assets/mockup-compute-admin.html`](054-assets/mockup-compute-admin.html)
> — concern 054's clickable three-identity, three-screen mock (A · add-machine, B · discover,
> C · machine dashboard; identities Current / Iskeru / Blueprint, switcher at mockup ~:449-453).
> 054 used it to settle *the model*; this concern is about delivering *the experience* it draws.
> The asset stays keyed to 054 (one spec may reference another's assets).

## Problem

The mockup captures the target UX, but delivering it is **not one change** — what it draws sits on
four different strata of the codebase, each with a different readiness:

1. **Pure presentation.** The live UI is a single fixed identity — spec-012's "cool-slate neutral
   ground, ONE accent (teal)" (`src/main/resources/static/tokens.css:4-6`, `--accent: #0891b2`
   `tokens.css:26`, `--radius: 6px` `tokens.css:64`, system font stacks `tokens.css:36-37`) with a
   left-rail nav (`static/index.html:35-43`). The mockup renders every screen in **three switchable
   identities** with their own token sets (mockup ~:67-121: "iskeru" is gold-gradient accent,
   `--radius:16px`, display-font headers ~:83-99; "blueprint" is a light drafting skin ~:101-121)
   over the *same* component vocabulary (`.card`/`.chip`/`.fchip`/`.legend` mirror app.css). That
   stratum needs no data at all — only tokens and CSS.
2. **A model→view seam — already specced (063).** Screen B's context-grouped discovery cards need
   the rich `AppPortItem` fields client-side, and today the DTO strips them:
   `MonitorDtos.AppPortView` is `(appName, port, runtime)` (`monitor/api/MonitorDtos.java:392`) and
   `RecipeDtos.RecipeView` carries no `appPortList` at all (`recipe/api/RecipeDtos.java:58`).
   Spec **063** (authored, **built — PR #88, open** — `063-todo-native-consumer-and-context-dto.md`) widens exactly
   that seam plus the native-consumer channel. It is referenced here, not reopened.
3. **Net-new backend.** Screen C's facts strip (OS · kernel · uptime · cores · RAM · arch,
   mockup ~:792-799) and SSH panel (handshake age/latency, key fingerprint, mockup ~:909-921) have
   **no server data**: the spec-018 facts probe reads only `/etc/os-release` + DMI vendor files into
   `MachineFacts(os, cloud)` (`machine/service/MachineFacts.java:12`,
   `MachineFactsProbe.java:15`), consumes them as one-shot add-only auto-tags
   (`Machine.java:100-109` — only `factsProbedAt` persists), and `MachineView` exposes
   `(id, name, host, port, loginUser, status, tags)` (`machine/api/MachineDtos.java:41`) — no fact,
   no probe timestamp, no handshake metric. Likewise "Recent runs" (mockup ~:882-908): the run
   engine exposes **no list endpoint** (`run/api/RunRS.java` — POST run :47, GET by id :62, cancel
   :73, children :85, SSE :92) and the UI's Runs screen is an explicit workaround — a 50-entry
   `localStorage["ca.runs"]` cache of "runs launched from this browser" (`static/app.js` ~:86-103,
   `screenRuns` ~:1227), while the server evicts rows after 24h / 500-per-action
   (`RunRowEvictionJob.java:53-54`).
4. **Forward model work, parked elsewhere.** The mockup's per-action **verb** badges
   (`.act-verb` — "restart"/"deploy"/"metrics", mockup ~:631-645) have no model field behind them
   (`RecipeDtos.ActionView` `recipe/api/RecipeDtos.java:83-86` — name/description/sudo/state/argv,
   no verb); its "declared app" context (mockup ~:734-740) is, by the mockup's own annotation
   (~:947), "a forward-looking sketch of 053's declared-app population". Both are concern-053 /
   spec-060 scope, unbuilt.

One spec cannot hold all four: the strata have different blockers (none / 063 / new endpoints+probes
/ an open concern), different risk (CSS vs migrations vs model), and in two places the mockup
**contradicts already-settled decisions** (see Open Questions) — those forks must be adjudicated
here, not silently built around in an implementation spec. A mega-spec would also violate the
one-logical-concern commit discipline the epic has kept since 055.

## Hypotheses / Options

**Working decomposition (the accepted hypothesis): one umbrella concern (this document) + six
children, each a single stratum with a single blocker profile.** Each child defers every fork below
to this concern.

| Child | Role | Stratum | Readiness |
|---|---|---|---|
| **[063](063-todo-native-consumer-and-context-dto.md)** (built, PR #88) | Native-consumer channel + rich-field DTO exposure — the model→view seam | server DTO (read-only) | **built** — BOL-892, PR #88 open, stacked on the 055-062 integration branch. Not rewritten here. |
| **065** | Selectable visual identity — port the mockup's identity-token architecture (Current/Iskeru/Blueprint) onto `tokens.css`/`app.css`; a persisted identity switch | presentation | **now** — no data dependency; pure token/CSS work over the existing component classes |
| **066** | Context-grouped discovery UI — **the 059-followup** (063 D5): 059 Surface 1 context cards + source notes + sibling scripts + approve-to-add rows, Surface-2 context grouping | presentation | **blocked by 063** — consumes `AppPortView`'s widened fields and `RecipeView.appPortList` |
| **067** | Machine-dashboard composition — recompose `screenMachineDetail` (`app.js` ~:607, today crumbs + pageHead + tags + discovery + recipe groups only) into mockup screen C: facts strip, per-machine tri-axis footprint (reusing Monitor's `axisMeter`/`consumerLegend`/`computeOther`, `app.js` ~:2436-2500), recipes, runs, SSH panel | presentation | **now** — layout + reuse; the 068 facts section is omitted until 068 lands, and runs degrade to the local `ca.runs` cache until 069 |
| **068** | Host facts & connectivity metadata — extend the spec-018 probe to a displayable facts record (kernel/uptime/cores/RAM/arch + probe timestamp, handshake latency), persist + expose on the UI DTO (S9: never on `McpMachineView`, `MachineDtos.java:59`) | server + presentation | **now** — independent of 063; needs migration + probe + DTO |
| **069** | Server-backed run history — a real list endpoint (per-machine + global) replacing the `localStorage` cache; retention already exists (`RunRowEvictionJob`) | server | **optional** — the mockup's "Recent runs" degrades to the existing cache; build only if the operator wants cross-browser history |

**Rejected shapes:**

- **One mega-spec** — mixes ship-now CSS with a 063-blocked surface, two server builds, and
  unresolved forks; unreviewable and unshippable as one branch.
- **Fold into 059** — 059 deliberately shipped as the pre-063 slice and 063's D5 already fixed the
  followup as "a dedicated 059-followup spec built on top" (`063:57-60`); reopening a done-shaped
  spec breaks the catalog contract.
- **Two-spec split (presentation / server)** — still couples the unblocked identity+dashboard work
  to the 063-blocked discovery UI on one branch, and couples optional 069 to needed 068.

## Open Questions

The mockup conflicts with settled decisions in places; the children **defer these forks to 064**
and build nothing that presumes an answer.

1. **Footprint placement on discovery cards — the load-bearing fork.** The mockup puts per-context
   DISK/RAM/CPU badges on the discovery card header
   (`specs/054-assets/mockup-compute-admin.html:624-626` — `<span class="dbadge">DISK 168 MB</span>`
   / `RAM 512 MB` / `CPU 24%` on the `/opt/lab/payments-api` card, ~:623). **059 Decision 1 says the
   opposite**: "Per-context **disk/RAM/CPU footprint is not shown on discovery cards** — those
   numbers are a product of the monitor poll loop (Surface 2), not of the one-shot discovery SSH
   sweep" (`059-todo-app-model-ui.md:46-50`; restated at 059:113-117, and echoed by 063's Known
   Gaps :117-119). Either 059 D1 stands (066 keeps discovery cards identity-only and links to
   Monitor) or it is explicitly revised (066 renders poll-sourced badges on discovery cards, with
   the staleness semantics that implies). **Interim (so 066 is not blocked on this fork): 066 builds
   the 059 D1 default — discovery cards stay identity-only and link to Monitor; a later reversal is an
   additive badge-row, not a rebuild.**
2. **Verb badges on action rows.** Mockup `.act-verb` chips (~:631-645) require a verb in the
   model; `ActionView` has none (`RecipeDtos.java:83-86`) and the closed verb vocabulary is
   **053/060 scope** (059:196-198 already fenced this). Do 066/067 render a verb slot that stays
   empty until 053 lands, or omit verbs entirely from this delivery? **Interim: children omit verbs
   pending resolution.** Nothing in 064's children may add a verb field.
3. **Declared apps.** The mockup's `notify-worker` "declared app · cron-launched · no port" card
   (~:734-740) sketches 053's declared-app population — unbuilt (mockup annotation ~:947 says so
   itself). In or out of 066's card states? (Out = the card type simply never occurs until 053.)
4. **Fonts.** The mockup's iskeru identity *declares* `"Inter"` / `"Space Grotesk"`
   (mockup ~:92-94) but ships no font files — it silently falls back to the system stacks the
   Current identity uses (`tokens.css:36-37`). If 065 adopts iskeru: self-host woff2s (asset weight,
   license check, offline-friendly) vs keep system stacks and accept the identity reads slightly
   different from the mock. No CDN — the app must work air-gapped.
5. **Iskeru's top-nav on mobile.** The mockup's only small-screen rule **hides the iskeru nav
   entirely** (`@media(max-width:820px){ body[data-identity="iskeru"] .pf-nav{display:none;} }`,
   mockup ~:437-440) — no toggle, no fallback. The live shell has a settled answer: spec-043's
   ≤720px stacked shell + `#nav-toggle` (`index.html:21-24`, `app.css:127-133`, ≤480px at
   `app.css:794-798`). 065 must reconcile the horizontal pill-nav with spec-043's collapse pattern
   before iskeru can be the shipped identity.
6. **Scope boundary.** In: 065-069 + the already-moving 063. Out (owned elsewhere): the verb &
   command contract and declared apps (**053**, and its MCP half **060**), standalone-DB sizing
   (**058** — a 066 card shows `—` until it lands, per 059:194-195). 069 is **in but optional** —
   dropping it leaves 067's runs section on the existing per-browser cache, which is a coherent
   (documented, `app.js` ~:86-88) state.

**Cross-references:** [054](054-concern-lightweight-app-model.md) (source concern; owns the mockup
asset) · [059](059-todo-app-model-ui.md) (the shipped UI slice whose Decision 1 is fork 1) ·
[063](063-todo-native-consumer-and-context-dto.md) (the seam; blocks 066) ·
[012](012-done-web-ui-and-design-system.md) (the design system 065 re-architects into identities).
