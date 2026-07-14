# 044 — Action approval UX (drawer review, split-button, action grid, machine naming)

**Status:** done · branch `moacyrricardo/spec-044-impl-action-approval-ux` (stacked on
`moacyrricardo/catalog-hygiene-015-028`) · no Linear issue (blocked for this repo; tracked as `spec-044`).

## Context

The recipe/action management surface works but is slow to operate:

- **Approving is a full-page round-trip.** On a machine page, `actionsList` (app.js)
  renders each action with a "Review / approve" link to a *separate* action page
  (`screenActionDetail`), where you read the command + params and click
  Submit/Approve/Revoke (`act(verb)`), then navigate back. For a box with a dozen
  discovered actions that's page → page → click → back, repeated.
- **Actions are full-width rows.** One action per full-width `ul.list` row → a long
  scroll on a busy machine.
- **Machines aren't consistently name-first.** Names exist (spec-028) and show in
  crumbs/page-head, but the host is shown raw and there is no quick copy.

The monitor already has the pattern we want: clicking an app opens a **drawer**
(`openConsumerDrawer` / `.drawer-backdrop` — a side panel ≥720px, a bottom sheet on
phones via spec-043) *without leaving the page*. There is also a `copy()` helper.

## Decision

Make the surface fast to scan and operate by reusing existing patterns (the drawer,
`copy()`, the `act(verb)` transitions, the spec-012 design system / `h()` /
textContent-only). Four changes:

1. **Review/approve in a side drawer, not a page.** Clicking an action opens the review
   **drawer** (command preview via `renderCommand`, params, approval state, the
   changed-since-approval banner) — the same component the monitor uses (side panel
   ≥720px, bottom sheet on phones per 043). No navigation away from the machine; closing
   restores focus to the list. The standalone action page stays reachable by URL
   (deep-link / no-JS fallback) but is no longer the primary path.

2. **Split (button-group) approve control per action.** Each action card carries a split
   button whose **default button is the primary transition for the current state**
   (PENDING_APPROVAL → **Approve**, DRAFT → **Submit**, APPROVED → **Run**), acting
   directly; a **caret** opens a menu of the other *valid* transitions (**See more…**,
   Approve, Reject/Disapprove, Revoke). "See more…" opens the review drawer. Only
   transitions valid for the current state appear/enable.
   - **Review-safety guard (honours the spec-004 gate intent).** One-click **Approve** is
     allowed only when the action is unchanged and being routinely (re)approved. A
     **first-time approval**, or any action flagged **`changedSinceApproval`**, makes the
     default button **"Review & approve"** which *opens the drawer first* — a human sees
     the exact command before it is armed. The gate itself is untouched (approval stays
     UI-only, per-(action,machine)); this is a client-side UX guard on top of it.

3. **Action grid, not full-width rows.** Render actions in a responsive grid — 2–3 per
   row on wide screens (`grid-template-columns: repeat(auto-fill, minmax(~320px, 1fr))`),
   collapsing to 1 column at spec-043's `--bp-sm`. Each card: name, state chip, sudo
   badge, description, and the split-approve control.

4. **Name-first machine identity + copy-host.** Everywhere a machine is identified (list,
   detail crumbs/page-head, monitor, run views) lead with `machine.name`; show
   `loginUser@host:port` as secondary muted text with a small **copy-host button**
   (reusing `copy()`). S9 is unaffected — this is the UI view (which already exposes
   host); the MCP views stay name/id-only.

## Implementation

- **`app.js`:**
  - Extract the review body from `screenActionDetail` into a reusable
    `renderActionReview(action, ctx)`, used by both the standalone page and a new
    `openActionDrawer(mid, rid, action)` that mirrors `openConsumerDrawer`.
  - A `splitButton({ primary, items })` helper: a `.btn-group` (primary `.btn` + caret
    `.btn`) toggling a `.menu` of secondary actions — keyboard-accessible,
    `aria-expanded`, closes on outside-click and route change, textContent-only. Wire
    each item to `act(verb)` or open-drawer; derive the primary + item set from
    `approvalState` + `changedSinceApproval` (the guard).
  - Replace `actionsList`'s full-width `ul.list` with an `.action-cards` grid of cards
    (identity + `splitButton`).
  - A `copyHostButton(machine)` (`btn--sm`) beside the name in the machine list, detail
    page-head, and other machine references; `copy(machine.host)` (or
    `loginUser@host:port`).
- **`app.css`:** `.action-cards` grid (`repeat(auto-fill, minmax(320px,1fr))`; 1 col
  ≤`--bp-sm`); `.btn-group` / `.menu` styles (theme-aware, tokens only); reuse the
  already-responsive drawer styles.
- **No backend change** — reuses the existing approval transitions (`act(verb)`:
  submit / approve / revoke [/ reject]) and the machine view (already carries name +
  host). Gate + `*ArchTest` untouched.

## Known Gaps

- **Direct-approve safety is a UX guard, not a gate change.** The server still approves
  on the action id; the "review first" rule lives in the client. Making the server
  require proof the command was shown is a possible later hardening, out of scope.
- **Reject vs revoke vocabulary** follows the transitions the API actually supports for
  the current state — the menu invents no new transition.
- **Bulk approve** (approve all pending on a machine) is excluded, so the per-action
  review guard stays meaningful.
- **No automated layout test** (grid/drawer/menu are CSS+JS); acceptance is manual,
  consistent with spec-043.

## Acceptance

From a machine page: clicking an action opens the review drawer *in place* (no
navigation); approve/revoke/etc. work from the drawer and the card's split button; a
first-approval or `changedSinceApproval` action routes through the drawer before arming;
actions display 2–3 across on desktop and 1 on a phone; every machine reads name-first
with a working copy-host button.

## How the implementation differed

Faithful to the Decision and Implementation. Notes:

- The standalone action page is the existing `screenApproval` function (the spec
  referred to it as `screenActionDetail`); its review body was extracted into
  `renderActionReview(action, ctx)` shared with the drawer, and it stays reachable
  by URL as the fallback.
- The backend supports `submit` / `approve` / `revoke` (no `reject`), so the split
  menu offers only those valid verbs plus "See more…" — no invented transition.
- `actionsList(machine, recipe, actions)` now takes the machine + recipe objects
  (was `mid, rid`) so the card can open the drawer and refresh the machine detail
  in place after a transition.
- The review-safety guard's "first-time approval" is derived from `!action.approvedAt`
  (never previously approved); that OR `changedSinceApproval` routes the primary
  through "Review & approve" (opens the drawer), never a direct one-click approve.
- Copy-host copies the raw `machine.host`; the button title shows the full
  `loginUser@host:port`. Wired into the machines list and the machine-detail page
  head (name-first crumbs also applied to the run-form).
- Testing: added `src/test/js/action-approval-ux.render-check.js` (the `node <file>`
  idiom, not wired into `mvn`). The full Maven suite stays green (263 tests) and the
  gate / `*ArchTest` are untouched (this is a UI-only change).

## Related

- spec-004 (the approval gate — sped up around, not weakened), spec-012 (design system /
  `h()`), spec-028 (machine name + the S9 MCP/UI view split), spec-034 (the drawer
  pattern reused), spec-043 (responsive — the grid + bottom-sheet drawer inherit its
  breakpoints).
