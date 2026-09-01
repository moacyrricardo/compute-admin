# 076 — Discovery family enablement affordance (opt-in vs default-on)

**Graduated from concern [074](074-concern-discovery-signal-to-noise.md) (open question E).** A
small, **presentation-only** fix for the enablement friction 074-E identified. Sibling of spec
[075](075-todo-cross-family-service-port-identity.md) (074 A/B, the discovery-accuracy half); this is
the discovery-*UX* half. Does **not** touch discovery accuracy (074 C→036, D→040) or the gate.

## Context

074 open-question E, answered: a user registering a real machine reported "a lot of things I had to
enable." Verified against the code — it is **not** a functional requirement:

- **6 of 7 families default to enabled** (`DiscovererFamily.java`): Nginx, Database, Cron, Systemd,
  Host vitals, Application monitor all `defaultEnabled=true`. **Only Docker** is `defaultEnabled=false`
  — deliberately, it is root-equivalent (it carries the one capability `note`).
- `DiscoveryEnablementService.familyStates` applies those defaults for a machine with no override rows
  (`:66` — `overrides.getOrDefault(f, f.defaultEnabled())`), so a freshly-registered machine **already
  probes the six** on the next "Discover recipes". Clicking an already-on family is a **no-op** (the
  boletim enablement rows were self-toggles: create- and update-timestamps seconds apart).

The friction is purely how the panel presents it. `discoverySection` renders **every** family as an
identical clickable `tag--filter` chip (`app.js:1218-1226`), keyed only on `f.enabled` for the on-state
— so the bar reads as "enable each family before discovering," when in truth only Docker needs turning
on. The signal that distinguishes them is **already on the wire**: `FamilyView` carries
`defaultEnabled` and `note` (`DiscoveryEnablementDtos.java:31`), but the client ignores `defaultEnabled`.

## Decision

**Client-only change** (`src/main/resources/static/{app.js,app.css}`); **no server / DTO / model /
migration / gate change** — the DTO already exposes what's needed.

1. **Distinguish opt-in from default-on in the family bar**, keyed on `f.defaultEnabled` (never
   hard-coded to "Docker", so it generalises if another opt-in family is added):
   - **Default-on families** render in an "already active" idiom — clearly *on and informational*, not
     an invitation to click to activate. They stay toggleable (a user can still disable one), but the
     affordance no longer implies enabling is a prerequisite.
   - **Opt-in families** (`defaultEnabled=false` — today only Docker) are visually marked as **opt-in**
     and grouped/labelled distinctly, keeping the existing root-equivalent `note`.
2. **One line of explanatory copy** above the bar: discovery runs the enabled families automatically;
   only the opt-in one(s) (root-equivalent, e.g. Docker) need turning on. Removes the "must enable
   everything first" misread.
3. **Accessibility:** the opt-in/default-on distinction must not be colour-only — carry a text label
   (an "opt-in" tag / heading), per the WCAG house rule (`app.css:751`); keep `aria-pressed` on the
   toggles.

## Implementation

- `app.js` `discoverySection` (`:1218`): split `families` into default-on vs opt-in (`f.defaultEnabled`);
  render the two groups with distinct treatment + the explanatory line; the opt-in group keeps the
  `f.note` sub-line (currently rendered at `:1228`). Build via `h()` (no `innerHTML`, spec-012).
- `app.css`: a small `tag--optin` (or an "opt-in" section label) variant + any grouping styles, in the
  existing chip idiom.
- **Restart `spring-boot:run` for the front-end change** (target/classes skew; project CLAUDE.md "UI
  evidence").
- **Tests:** a headless render-check (the `src/test/js/*.render-check.js` idiom) asserting that a
  `defaultEnabled=false` family (Docker) is marked opt-in and the default-on families are not presented
  as needing enablement, and that the explanatory copy renders. No Java test needed (no server change).

## Known Gaps

- **Presentation only** — enablement *semantics* and *defaults* are unchanged; this changes how they
  read, nothing about what probes run.
- **Live evidence** for this feature is a UI GIF and needs the **dev** profile (real families list);
  the demo profile's canned fleet is fine for the family bar too, but the change is host-independent.
- Out of scope: discovery accuracy/noise — nginx `:80`/`:443` shared identity + cross-family dedup are
  spec 075; systemd flood is concern 036; monitor-shows-only-enabled is concern 040.
