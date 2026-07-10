# 007 — Custom-command recipes

## Context

Beyond the built-in service types, operators have their own scripts already on
the box (e.g. `/home/ec2-user/app/minhabufunfa/run.sh`). This spec wraps such an
existing script as a `CUSTOM` recipe/action so it flows through the **same** gate
(004) and run path (005) as everything else — no bypass.

## Decision

A custom action is an ordinary `Action` (004 model) whose first argv token is a
`LITERAL` holding the **absolute script path**, optionally followed by typed
`PARAM` tokens. It uses the identical approval state machine, content-hash
binding, and run path.

## Implementation

- `Recipe.type = CUSTOM`.
- `ActionService.addCustomAction(CustomActionInput(machineId, name, scriptPath,
  List<ParamDefInput>, boolean sudo))`:
  - Validate `scriptPath` is an **absolute** path (`/…`) at authoring time; store
    it as the leading `LITERAL` `ArgToken` — it is **not** a param, so it can't
    vary at run time.
  - Append declared params as `PARAM` tokens with their `ParamDef`s; the same
    add-action validation (004) applies.
  - Created in `DRAFT`; runs only once `APPROVED`; subject to the 004 snapshot
    hash and the 005 run path.
- No **free-form command** param is ever allowed (S4) — only declared typed
  params vary; the path and command shape are fixed literals.
- UI + MCP `add_recipe`/`add_action` (008) reach this via `ActionService`.

**Tests.** `CustomActionTest`: absolute-path validation (reject relative/blank);
path stored as leading literal; a declared param binds as a later argv element;
the action runs through the normal `RunService` gate.

## Known Gaps

- **Approval binds to a path, not to contents.** The bytes behind `scriptPath`
  can change on the box after approval; there is no checksum/content pinning in
  v1. Combined with S5 (`sudo`), if that path is writable by a lesser-privileged
  user it is a privilege-escalation vector. Content pinning (hash the script at
  approval, verify before run) is a candidate hardening spec.
