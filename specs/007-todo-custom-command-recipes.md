# 007 — Custom-command recipes

## Context

Beyond the built-in service types, operators have their own scripts already on
the box (e.g. `/home/ec2-user/app/minhabufunfa/run.sh`). This spec wraps such an
existing script as a custom recipe/action so it can flow through the same
approval gate and run path as everything else.

## Decision

- `Recipe.type = CUSTOM`. A custom action's `commandTemplate` invokes an
  **absolute script path on the target**, optionally with named, typed params
  bound as argv (same param model as 004).
- Custom actions use the **identical gate**: created in `DRAFT`, run only once
  `APPROVED`, subject to the 004 snapshot binding and the 005 run path. No custom
  bypass.
- No "free-form command" param is ever allowed (ARCH.md S4). The script path is a
  fixed part of the template; only declared typed params vary.

## Implementation

- Extend `RecipeService`/`ActionService` to author a custom action from
  `(machineId, scriptPath, params?, sudo?)`.
- Validate that `scriptPath` is an absolute path at authoring time; it is stored
  as a fixed template segment, not a param.
- UI + MCP `add_recipe`/`add_action` (008) reuse this path.

## Known Gaps

- **Approval binds to a path, not to contents.** The bytes behind `scriptPath`
  can change on the box after approval; there is no checksum/content pinning in
  v1. Combined with S5 (`sudo`), if that path is writable by a lesser-privileged
  user it is a privilege-escalation vector. Content pinning (hash the script at
  approval, verify before run) is a candidate hardening spec.
