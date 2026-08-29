# 066 — Context-grouped discovery UI (059-followup)

**Status:** todo · Linear [BOL-895](https://linear.app/iskeru/issue/BOL-895) · build branch `moacyrricardo/bol-895-cpt-066-context-grouped-discovery-ui` · **Part of concern 064 (mockup delivery).** **BLOCKED BY 063** (BOL-892 — the DTO seam this spec renders).

## Context

This spec is **exactly what 059 deferred**. 059 (built on `moacyrricardo/bol-888-cpt-059-app-model-ui-new-surfaces` — PR #86, close-out pending merge) set out to render discovery grouped by 055's app contexts, and its Implementation Notes record the wall it hit: the context model — `contextDisplay`, `scriptFolder`, `contextScripts`, `sourceNote`, `confidence` — is persisted on the recipe's `app_port_list` CLOB but **stripped at every client-facing DTO**. On this branch that is still true: `MonitorService.AppPort` is `record AppPort(String appName, int port, String runtime)` (`monitor/service/MonitorService.java:78`), `MonitorDtos.AppPortView` mirrors the same three fields (`monitor/api/MonitorDtos.java:392–394`), and `RecipeDtos.RecipeView` (`recipe/api/RecipeDtos.java:58`) carries no `appPortList` at all. 059 therefore shipped only the client-computed confidence badges (`_ramLow`/`_diskLow` renders, `app.js:2783,2791`) and deferred Surface 1 plus the Surface-2 context grouping "to a sibling read-only exposure spec".

That sibling is **063** ([063](063-todo-native-consumer-and-context-dto.md)), which widens `AppPort`/`AppPortView` with `contextDisplay`, `contextScripts`, `sourceNote`, `confidence`, and the logical `scriptFolder`; adds `RecipeView.appPortList : List<AppPortView>`; and derives **native consumers** (`NativeConsumerData`, grouped by `contextKey`, `source=NATIVE`) feeding the existing `MonitorDtos.MonitorConsumerView` (`MonitorDtos.java:265`) — replacing today's one-consumer-per-`appName` mapping (`MonitorConsumerView.ofNativeApp`, `MonitorDtos.java:142,278`). 063 is explicitly **seam-only** (its D5): it carries the data; the surfaces are this spec. **066 cannot build until 063 lands.**

The surface being extended is the machine-detail Discovery panel, `discoverySection(p, mid, families)` (`app.js:753`): today a `.section` with per-family `.filter-chips` toggles (`toggleFamily` PUT `/machines/<mid>/discovery/<key>`, `app.js:788`) and a "Discover recipes" button — no grouped list at all; proposed actions render flat in the recipe groups that `screenMachineDetail` assembles from the recipe channel (`data.groups`, `app.js:642`). The target experience is the mockup's **Screen B** (`054-assets/mockup-compute-admin.html:581–765`): one `.ctx` card per context with a mono path header (`.ctx-path`, :619), a source note (`.ctx-src`, :620 — "app folder · discovered via port :8080 + systemd unit"), and per-action rows with an "Approve → add recipe" button (:635). The mockup's `.dbadge` footprint metrics on those cards (:622–627) and its `.act-verb` chips (:631) are **design forks recorded in concern [064](064-concern-mockup-delivery.md)** — see Known Gaps.

## Decision

1. **Discovery renders as Screen B: one context card per resolved context.** Grouped by 055's `contextDisplay` (via 063's widened DTOs), each card carries: the **logical path header** (mono, the `crumbs`/mono idiom, `app.js:276`), the **source note** (063's `sourceNote`) as a dim sub-line, the **sibling-script list** (055 dec. 4, 063's `contextScripts`) by basename, and the record's **confidence** rendered as a text label/badge — never colour alone (the WCAG house rule, `app.css:743`). Non-listening contexts appear as ordinary cards with an empty port list, labelled as such. This restates 059 Decision 1 and builds it for real.

2. **Per-action approve-to-add reuses the EXISTING spec-044 approval path — no new endpoint.** Each proposed action row inside a card gets the mockup's "Approve → add recipe" affordance, implemented as the existing `approvalSplit(machine, recipe, action)` control (`app.js:703`) — `DRAFT`→Submit, `PENDING_APPROVAL`→Review & approve via `openActionDrawer`/`screenApproval` (`app.js:957`), all through the existing `POST /actions/<id>/<verb>` (`actVerb`, `app.js:740`). The gate stays singular and UI-only (ARCH gate point 2); 059's note that spec-044 already satisfies this holds — 066 relocates the rows into context cards, it does not add an approval mechanism.

3. **Action rows come from the recipe channel; context identity from 063's DTOs.** `GET /machines/<id>/discovery` returns only `{families}`; the proposed actions (rid/aid/approvalState) already arrive on `data.groups` (`app.js:611–621,642`). 066 **re-groups `data.groups` by the context fields on 063's `RecipeView.appPortList`** to attach each action to its card — no new endpoint, no payload added to the discovery channel (the 059 data plan, now actually possible).

4. **Surface-2 context grouping: the Monitor dashboard groups native consumers by context.** 063's `parseNativeConsumers` emits one `MonitorConsumerView` per context (`name = contextDisplay`), so the tri-axis meters and legend group by context automatically once rendered; 066 keys the legend chip and consumer drawer on that per-context consumer. `axisMeter` (`app.js:2458`), `consumerLegend` (`app.js:2493`), and `computeOther` (`app.js:2436`) are **structurally untouched** — its `attr()` blindly sums rendered consumers (`app.js:2437–2441`), so per-context native segments subtract from OTHER by construction (057's single-denominator invariant, not re-implemented here).

5. **The per-family `.filter-chips` stay.** The family enablement bar (`app.js:754–762,783`; `.tag--filter`, `app.css:244`) remains as a filter above the grouped cards — grouping and filtering coexist (059 Decision 1's coexistence rule). A context card shows when its discovering family is enabled.

6. **No footprint badges on discovery cards.** The mockup puts DISK/RAM/CPU `.dbadge`s on `.ctx-head` (mockup `:622–627`); 059 Decision 1 moved footprint to the Monitor route (poll data, not sweep data). That conflict is a concern-064 **Open Question** — 066 defers to it and builds cards with identity + structure only, linking to Monitor for live footprint.

## Implementation

All work is in `src/main/resources/static/{app.js,app.css}` — presentation only, on top of 063's landed DTOs. Build through `h()` (`app.js:23`), never `innerHTML` (spec-012 XSS discipline: host-derived strings — paths, source notes, script names — reach the DOM only as text). **Restart `spring-boot:run` for every front-end edit** (target/classes skew; project CLAUDE.md "UI evidence").

### Context cards inside `discoverySection`

- Keep the existing family bar + Discover button (`app.js:753–786`) untouched at the top of the `.section`.
- Below them, build the grouped list: index `data.groups` (`app.js:642`) by each recipe's `appPortList[].contextDisplay` (063's `RecipeView` field); one `.card`-scaffolded context card per distinct context, ordered stably (e.g. by display path).
- **Card header:** the `contextDisplay` path in the mono idiom; `sourceNote` as a `.small dim` sub-line; a text confidence label (`high`/`medium`/…) rendered as a neutral `.tag` — chip semantics reserved for approval states (`chip()`, `app.js:197`; state map `app.js:190`).
- **Card body:** the `contextScripts` basenames as a compact list; then one row per proposed action — action label + argv summary (text), its approval-state chip, and the `approvalSplit` control (`app.js:703`) verbatim. After approval, the existing reconciliation shows the recipe in the normal list; nothing bypasses `screenApproval`'s review path (`app.js:717–722` forces Review & approve when unreviewed).
- **Fallbacks (063's tolerant-reader contract):** a group whose `appPortList` is empty or context-less (old rows) renders in an "ungrouped" trailing section styled like today's flat groups — no data is hidden by the regroup.
- A card with an empty port list gets a "no listening port" text label (054 D4 non-listening apps).

### Surface-2 grouping

- The consumer model assembly (`app.js:2400–2423`) already generically maps `MonitorConsumerView`s; 063's per-context native consumers flow through with no new client type. 066's delta: surface `contextDisplay` as the consumer's display name in the legend chip and drawer title, and list the context's `contextScripts`/apps in the drawer body (`openConsumerDrawer`, `app.js:2896`) — replacing the per-`appName` native cards, whose "native process — no attributable disk footprint" caveat (`app.js:2915`) now applies per context.
- `computeOther` (`app.js:2436`) and the 059 low-confidence text badges (`_ramLow`/`_diskLow`, `app.js:2783,2791`) are reused unchanged.

### Styling

New CSS is limited to context-card layout (path header / source-note sub-line / script list / action rows), through existing tokens and classes (`.card`, `.section`, `.tag`, `.chip--*`, `.filter-chips`, spec-043 breakpoints `app.css:807,836`). The mockup's `.dbadge`, `.ctx-*` skin, and `body[data-identity="iskeru"]` rounding (mockup `:389–397`) are **not** ported — visual identity is spec 065.

### Tests

Extend `src/test/js/app-model-ui.render-check.js` (the 059 harness): grouping of `data.groups` by `appPortList` context fields (multi-recipe context, context-less fallback, empty-port label); the approve control on a grouped row is `approvalSplit` output; a per-context native consumer renders one legend chip named `contextDisplay`. No server tests — 063 owns the DTO tests.

## Known Gaps

- **BLOCKED BY 063.** On this branch `AppPortView` is still `(appName, port, runtime)` (`MonitorDtos.java:392`) and `RecipeView` has no `appPortList` (`RecipeDtos.java:58`) — nothing to render until 063 merges. 066 stacks on 063's branch.
- **Footprint badges on discovery cards are a concern-064 Open Question.** The mockup shows them; 059 Decision 1 says no (poll data belongs to Monitor). Deferred — this spec ships without them; if 064 resolves the other way, the badge row is a small additive follow-up.
- **Verb badges on action rows (mockup `.act-verb`, `:631`) are not built** — there is no verb field in the model; the closed verb vocabulary is 053/060 scope (a 064 fork). Rows show the action label the discoverer proposed.
- **Declared apps (mockup's DECLARED card, `:748`) are 053 scope, unbuilt** — no declared-context card renders until that model exists (a 064 fork).
- **`contextKey` is never fetched or rendered** (063 D3): the UI keys on `contextDisplay`; the internal dedup key stays server-side.
- **Visual identity is 065**: these cards render in the existing spec-012 slate/teal system; the rounded iskeru skin is not this spec's work.
