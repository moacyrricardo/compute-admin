# Demo recording steps

Interaction scripts for the three flows. Written **tool-agnostic** (base URL + visible
text + waits) so either the `test-flow-headless` agent or [`record.py`](./record.py) can
drive them. Base URL `http://localhost:8099` (the demo port). Log in first as
`demo@example.com` / `demo-pass` (see [fake-fleet.md](./fake-fleet.md)).

**Selectors:** prefer **visible text** (the UI uses semantic `<button>`/`<a>` from the
`h()` helper — no stable ids on most controls). Where a control is text-only, the step
names the exact label; if a label changes, update it here (see README maintenance map).

**Mobile pass (viewport 390×844):** the shell collapses (spec-043). Before using the
nav, **tap "Menu"** to open it; it auto-closes on navigation. The consumer drawer opens
as a **bottom sheet**. Steps that differ are marked _[mobile]_.

**Pacing:** ~1.2s dwell after each navigation/click so the GIF is readable; ~2.5s on the
final frame of each flow. Recording starts once logged in and on the first route below.

---

## §1 — Add machine + discover  (`01-add-machine-discover`)

1. Go to `#/machines`. _[mobile]_ tap **Menu** → **Machines**. Wait for the list (shows
   the pre-seeded **api-prod-2**).
2. Click **Register machine**. Wait for the register form (`#/machines/register`).
3. Fill: **Name** = `web-prod-1`, **Host** = `10.10.0.11`, **User** = `web`,
   **Port** = `22`.
4. Click **Register & test connection**. Wait for the toast (`Registered — connection …`)
   and redirect to the machine detail (`#/machines/<id>`).
5. In the **Discovery** panel, ensure **Docker** is enabled (tap it on — default off per
   spec-035), then click **Discover recipes**. Wait for the toast `Discovery complete`
   and for recipe groups to render (monitor machine, the app monitors, the docker
   datastore).
6. Hold ~2.5s on the populated machine page. **End.**

## §2 — Enable monitor recipes/actions  (`02-enable-monitor`)

Precondition: web-prod-1 exists with discovered `PENDING_APPROVAL` recipes (end state of
§1). Start on its machine detail page.

1. Scroll to a monitor recipe group (e.g. **monitor machine** — host vitals `cpu`, `ram`,
   `disk`, `cores`).
2. For each action, click **Review / approve** → the action page; read the command;
   click **Approve**; return (**Back to review**/breadcrumb). Repeat for the app-monitor
   check(s) + the docker checks.
   - _[when spec-044 lands]_ this becomes: click the action → **review drawer** opens;
     click the split-button **Approve** (or open the drawer for a first/changed action).
     **Rewrite this section against 044 at that point.**
3. After approving, the action state chips flip to **APPROVED**. Hold ~2.5s. **End.**

_(Repeat conceptually for api-prod-2 — but it is pre-seeded already-approved so GIF 3 has
data; only web-prod-1 is approved on camera here.)_

## §3 — Monitor fleet view  (`03-monitor-fleet`)

Precondition: both machines have approved monitor recipes + host vitals (`nproc`/`cores`,
`free`, `top`, `df`) so the axes + other/system segment fill.

1. Go to `#/monitor`. _[mobile]_ tap **Menu** → **Monitor**. Wait for both machine
   sections to render (**web-prod-1**, **api-prod-2**).
2. Set **Cadence** = `5s` (or click **Run now**) so the RAM/CPU/disk bars populate.
   Wait until the segmented bars show per-app segments + the datastore + the other/system
   segment.
3. Point out the fleet: each machine has **2 apps** and a **shared database** — web-prod-1's
   is **docker-sourced**, api-prod-2's is **native**. (No click; just let it settle ~2s.)
4. Switch the **View** lens to **Databases**. Confirm both datastores appear in the
   **Shared** band (used-by each machine's two apps).
5. Click a consumer (e.g. web-prod-1's `postgres`) → the **drawer** opens (side panel;
   _[mobile]_ **bottom sheet** sliding up) showing its axes + services/probes.
6. Close the drawer. Hold ~2.5s on the fleet. **End.**

---

## Note for the runner

The `test-flow-headless` agent does **not** start the server, seed data, or publish. The
runner must: boot the app in the `demo` profile (README run procedure), then hand the
agent **one section at a time** with the target viewport, collect its gif, and name it
`NN-slug.<viewport>.gif` under `demo/out/`.
