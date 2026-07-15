# 047 — Config to `.properties` (drop YAML)

**Status:** todo · no Linear issue (blocked; tracked as `spec-047`). Graduated from concern
[045](./045-todo-arch-cleanups.md) §3.

## Context

Spring config is YAML: **3 files, 65 lines** — `application.yml` (41), `application-dev.yml`
(8), `application-demo.yml` (16). The team prefers `.properties`; there are no `.properties`
files today. Pure preference, net-neutral, no behaviour change.

## Decision

Convert all three YAML files to `.properties`, delete the `.yml`. Behaviour and all keys
identical; only the serialization format changes.

## Implementation

- For each file, translate to dotted keys:
  - scalars → `a.b.c=value`; lists → indexed keys `a.b[0]=…`, `a.b[1]=…`; inline maps →
    `a.b.key=value`.
  - `src/main/resources/application.yml` → `application.properties`
  - `application-dev.yml` → `application-dev.properties`
  - `application-demo.yml` → `application-demo.properties` (added by spec-035; the demo
    datasource + `server.port` override).
- **Pre-check (Known-Gap trigger):** confirm none of the YAML uses a construct `.properties`
  can't express cleanly — **multi-document `---` blocks**, **`spring.config.activate.on-profile`
  sections**, or **profile groups** (`spring.profiles.group.*`). If found, split into
  per-profile files or express groups as `spring.profiles.group.<name>[0]=…`. (Current files
  are small and per-profile already, so this is expected to be trivial — verify.)
- **Verify:** boot each profile (`dev`, `demo`, default) and confirm the same effective
  config (datasource URL, port, Flyway, any `ca.*` app props) resolves. `mvn test` green
  (tests use `@SpringBootTest` config resolution).

## Known Gaps

- If a YAML-only construct is present (profile groups / multi-doc), the properties form is
  slightly more verbose — accepted (the whole point is the format preference).
- No config *values* change; this is not a config-hardening spec.

## Related

concern [045](./045-todo-arch-cleanups.md) (§3), spec-035 (`application-demo.yml`).
