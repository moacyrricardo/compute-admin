# 042 — Blueprint authoring UI (the command-builder form)

**Status:** todo · no Linear issue (blocked for this repo; tracked as `spec-042`).

## Context

Blueprints (spec-010) are "author once, instantiate per-machine" recipe templates. The
**backend is complete** — `BlueprintRS` exposes create / list / get / edit-blueprint /
list-actions / add-action / edit-action / instantiate, and four MCP tools mirror it. The
**web UI is not**: `screenBlueprints` / `screenBlueprintDetail` (app.js) wire only
create, list, view, and instantiate. The authoring middle is missing:

| `BlueprintRS` endpoint | UI? |
|---|---|
| `POST /blueprints`, `GET /blueprints`, `GET /{id}`, `GET /{id}/actions`, `POST /{id}/instantiate` | ✅ |
| **`POST /{id}/actions`** (add action) | ❌ — the critical gap |
| **`PUT /{id}`** (edit name/description, bumps version) | ❌ |
| **`PUT /actions/{actionId}`** (edit action) | ❌ |

Consequences:

1. The create form makes a blueprint with **only a name + description** — no actions.
   The detail screen *displays* actions but its empty state is a dead end (*"This
   blueprint has no actions yet"*) with **no add affordance**. So a UI-authored blueprint
   is permanently empty, and instantiating it yields per-machine recipes with **zero
   actions** — the feature does nothing end-to-end from the browser. Only the MCP tools /
   raw REST can populate a blueprint today.
2. **Root cause:** there is **no command-authoring form anywhere in the UI.**
   `renderCommand` only *displays* existing `argTokens`/`paramDefs` read-only; nothing
   *builds* them. Regular recipe actions dodge this because they arrive via **discovery**
   — but blueprints have no discovery path, so the UI is the only authoring surface and
   it lacks the one form that matters.
3. The version-bump → reconcile → DRAFT-reset flow (the whole point of blueprint
   versioning) cannot be exercised from the browser (no edit UI).

Secondary rough edges: instantiate silently **drops checked machines** when a tag is also
typed (the backend wants exactly one); the machine picker labels rows `loginUser@host`
instead of the spec-028 `name`; there is no delete (the backend has none either).

## Decision

Build a reusable **command-builder form** — an ordered argToken list (LITERAL / PARAM) +
a paramDef editor (ALLOWED_SET / REGEX / INT_RANGE) + the sudo flag — matching the
backend authoring validation (spec-004/007: every PARAM token names a declared def, every
declared def is referenced, a CUSTOM action's leading literal is an absolute path). Wire
it into the Blueprints detail screen for **add-action** and **edit-action**, add an
**edit-blueprint** (name/description) control, and **fix the instantiate target
selection**. Write the builder so it is reusable by the (currently also absent)
custom-recipe-action authoring path.

## Implementation

- **Command-builder component (`app.js`).** A form producing the POST/PUT body: ordered
  `argTokens` (`{kind: LITERAL|PARAM, value|paramName, position}`) + `paramDefs`
  (`{name, kind, allowedValues|regex|min|max}`) + `sudo`. Live preview through the
  existing `renderCommand`. Client-side validation mirrors the server (referenced-vs-
  declared params; absolute-path leading literal for CUSTOM). textContent-only,
  theme-aware — spec-012 idiom, via the `h()` helper (no `innerHTML`).
- **Add action:** an "Add action" button on `screenBlueprintDetail` → the builder →
  `POST /blueprints/{id}/actions` → refresh.
- **Edit action:** each action row gets an Edit control → the builder pre-filled →
  `PUT /blueprints/actions/{actionId}`; surface that this bumps the blueprint version and
  that instantiated copies reset to DRAFT on the next re-instantiate.
- **Edit blueprint:** name/description edit → `PUT /blueprints/{id}` (bumps version).
- **Instantiate fix:** make machines-vs-tag mutually exclusive in the UI (disable the
  checkboxes when a tag is entered, or radio-style) to match the backend's exactly-one
  rule, so checked machines are never silently dropped; label picker rows with the
  machine `name` (spec-028), not `loginUser@host`.

## Known Gaps

- **No delete** for blueprints or actions — the backend has no DELETE endpoint either;
  adding one is a separate small spec (model + RS + MCP + gate test).
- **Custom-recipe-action authoring** is also missing in the UI (same root cause). This
  spec builds the reusable component but only wires it into Blueprints; wiring it into
  recipe custom actions is a fast follow.
- **No auto-instantiation / drift enforcement** — unchanged from spec-010's Known Gaps;
  out of scope.

## Acceptance

From the browser: create a blueprint, **add an action** with a couple of params via the
builder, instantiate onto a machine → the machine gets a recipe whose action is
`PENDING_APPROVAL` with the authored command — the full author→instantiate→approve loop
works without touching MCP or raw REST.

## Related

- spec-010 (recipe blueprints — the backend this completes), spec-004 (action/param model
  + gate + content-hash edit), spec-007 (CUSTOM absolute-path rule), spec-012 (UI design
  system / the `h()` helper), spec-028 (machine `name` — the picker-label fix).
