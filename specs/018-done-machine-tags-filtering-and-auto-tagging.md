# 018 — Machine tags: filtering & auto-tagging

> **Status: done.** Branch `moacyrricardo/spec-018-machine-tags-filtering-and-auto-tagging`
> (stacked on spec-019). Linear is blocked for this repo, so there is no issue id.
>
> **How the implementation differed from / refined the spec:**
> - `MachineRepository.findByOwner_IdAndTags_NameIn` is named exactly as specified;
>   the ManyToMany join can repeat a machine once per matching tag, so `MachineService.list`
>   de-dupes by id (preserving order) rather than relying on a `Distinct` finder.
> - `MachineService.list` now takes `List<String>` (OR semantics); `MachineRS.list` binds
>   a repeatable `?tag` param to that list and `list_machines` takes a `tags[]` argument.
> - The once-per-machine guard is a `@NotAudited Instant factsProbedAt` column on `Machine`
>   (migration V8, base table only — no `machine_aud` column). Because it is non-audited,
>   the auto-tag write opens no Envers revision, so the spec-019 revision-count invariants
>   are untouched (verified by `MachineReachedEventTest`). Its presence is what keeps
>   auto-tagging one-shot and prevents re-adding a user-removed auto-tag.
> - The facts probe (`MachineFactsProbe`) hangs off spec-019's `MachineReachedEvent` via
>   `machine/event/MachineFactsTagger` (`@Async` on `machineEventExecutor`), reading the box
>   with `cat` only (no sudo); the RHEL family normalises to `rhel`, unknown IDs / absent
>   DMI signals yield no tag.
> - Tests: `MachineServiceTest` (login-user guess, OR filter + owner scoping, add-only
>   refine + once-guard), `MachineFactsProbeTest` (os-release / DMI parsing),
>   `MachineFactsTaggerTest` (event → probe → add-only tags, once-per-machine).

## Context

Machines already carry free-form, owner-scoped **tags** today: the `Tag` entity
(`machine/model/Tag.java`, unique per `(owner, name)`, get-or-created by
`MachineService`, `@NotAudited` — labels only), a `machine_tag` join on
`Machine.tags`, the `tag_machine` MCP tool, and tag fields on `MachineDtos`. What's
missing is (a) using tags to **filter** the machines list, and (b) **auto-tagging**
so a machine is labelled without the user hand-typing tags.

Per the product decision, auto-tags come from **two sources**: a quick guess from
the **SSH login user** at registration, refined by **real OS/cloud detection** on
the first successful SSH reach.

## Decision

**Filtering.**
- **UI:** the Machines list renders the set of tags present across the user's
  machines as filter chips; selecting one or more narrows the list (client-side over
  the already-loaded set — the instance is single-user-scale). Multiple selected tags
  are AND/OR — default **OR** (show machines having any selected tag), with a note in
  Known Gaps if AND is later wanted.
- **API/MCP parity:** `GET /api/machines` accepts an optional `?tag=<name>` (repeatable)
  filter, and `list_machines` gains an optional `tags` argument, so an agent can scope
  by tag too. Owner-scoping is unchanged (a user only ever sees their own machines).
  Add `MachineRepository.findByOwner_IdAndTags_NameIn(...)` (owner-scoped, distinct).

**Auto-tagging (two sources, add-only, user stays in control).**
1. **Login-user guess (at registration).** A small curated map from SSH login user →
   tag applied when the machine is created: e.g. `ec2-user`→`aws`, `centos`→`centos`,
   `debian`→`debian`, `ubuntu`→`ubuntu`, `admin`/`root`→(none). These are *provisional*
   best-guesses (note: `ubuntu` is also the default AWS Ubuntu AMI user, so this is
   fuzzy — that's why step 2 refines it).
2. **OS/cloud probe (on first successful reach).** A read-only facts probe over SSH
   reads `/etc/os-release` (→ `ubuntu`/`debian`/`alpine`/`rhel`/… from `ID`) and a
   best-effort cloud signal (e.g. DMI vendor at `/sys/class/dmi/id/*` or a
   cloud-metadata reachability check → `aws`/`gcp`/`azure`/`magalu`). It applies the
   accurate tags. This probe is **read-only** (honours discovery's "never mutate the
   box" rule) and hangs off the **`MachineReached` event from spec 019** — the same
   "we just reached this machine" signal that updates connectivity status also triggers
   a one-time facts probe → auto-tag. (If 019 doesn't land first, run the probe inside
   discovery instead.)

Auto-tags are applied **add-only** and are ordinary tags thereafter: the user can
remove any of them, and a manual tag is never touched by auto-tagging. Auto-tagging
runs at registration (source 1) and on the first successful reach (source 2); it does
**not** continuously reconcile (see Known Gaps).

## Implementation

- **Tagging service:** extend `MachineService` with `applyLoginUserTags(machine)` (the
  static map, called from `register`) and `applyDetectedFacts(machineId, facts)` (called
  by the facts-probe listener). Reuse the existing get-or-create tag path so no
  duplicate `Tag` rows and owner-scoping stays centralised.
- **Facts probe:** a small `MachineFactsProbe` (in `machine` or `discovery`) that runs
  the read-only `cat /etc/os-release` + DMI/cloud checks via the `SshExecutor` port and
  returns a `MachineFacts(os, cloud)` record. Invoked once per machine on first
  successful reach (via the 019 event listener) — outside any long DB transaction
  (mirroring spec-013 H3).
- **Filtering:** `MachineRepository` finder + a `?tag` query param on `MachineRS.list`;
  `MachineDtos` already exposes tags. UI filter chips in the Machines screen
  (`app.js`), text-node safe (spec-012 XSS discipline).
- **Tests:** login-user map applies the expected provisional tag at register; the facts
  probe maps `/etc/os-release` output to the right OS tag and refines the provisional
  one; filtering returns only owner-scoped machines with the tag; auto-tag is add-only
  (a user-removed tag is not re-added on the same run).

## Known Gaps

- Login-user heuristic is deliberately fuzzy (`ubuntu` user ≠ always Ubuntu); step 2 is
  the source of truth. The curated map starts small and grows by need.
- Auto-tagging is not a continuous reconciler — it runs at registration + first reach.
  Re-detecting on OS change / re-imaged host is a later concern.
- Cloud detection is best-effort (DMI/metadata heuristics), not an authoritative cloud
  API (that's the parked spec 009 cloud-import territory).
- OR-only multi-tag filter initially; AND-filter deferred.

## Sequencing

Composes with **spec 019** (the `MachineReached` event drives the facts probe). If 019
lands first, 018's probe subscribes to it; otherwise 018 runs the probe inside discovery
and is retrofitted onto the event later. Builds on the existing tag model (spec 003).
