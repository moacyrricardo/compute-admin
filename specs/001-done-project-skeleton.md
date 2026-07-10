# 001 — Project skeleton

> Status: **done** — branch `moacyrricardo/spec-001-project-skeleton`. Linear is
> BLOCKED for this repo, so no issue identifier.

## Context

compute-admin is greenfield. Before any feature can land we need a buildable,
runnable Spring Boot base that fixes the framework posture ARCH.md commits to and
the conventions inherited from birthday-rsvp (see ARCH.md "Code conventions").
Every later spec assumes this skeleton exists. This spec adds **only** the base:
build, wiring, health, a static shell, and the dev run recipe — no domain.

## Decision

Stand up the Maven project so `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
boots, serves `GET /api/health`, and serves a static UI shell. RESTEasy is the
JAX-RS dispatcher; no Spring MVC controllers. Flyway owns the schema from commit
one. Envers is configured but audits nothing yet.

## Implementation

**Build (`pom.xml`).** `groupId com.iskeru`, `artifactId compute-admin`, Spring
Boot **3.5.x**, Java **25**. Pin the parent to the **latest 3.5.x patch** rather
than the first `3.5.0` release: `3.5.0` (May 2025) predates the Java 25 GA
(Sep 2025), so its bundled Byte Buddy / CGLIB is a runtime risk for Hibernate
proxying and `@Configuration` CGLIB; the current patch ships Java-25-capable
Byte Buddy and boots cleanly on the JDK 25 toolchain. Dependencies:
- `spring-boot-starter-web` (embedded Tomcat only; MVC unused)
- `resteasy-servlet-spring-boot-starter` (JAX-RS dispatcher)
- `com.h2database:h2`
- `org.flywaydb:flyway-core`
- `spring-boot-starter-data-jpa`
- `org.hibernate.orm:hibernate-envers`
- `org.projectlombok:lombok`
- test: `spring-boot-starter-test`

Explicitly **not** included: `spring-boot-starter-validation` (validation is
manual, per conventions).

**Package tree** (base `com.iskeru.computeadmin`):
```
Application.java                     # @SpringBootApplication
config/  JaxRsApplication.java        # @Component @ApplicationPath("/api"), empty body
common/  HealthRS.java, CommonDtos.java, HtmlEscaper.java
```
Feature-module packages (`machine`, `recipe`, …) and `audit` arrive with their
specs.

**Health endpoint.** `common/HealthRS` — `@Component @Path("/health")
@Produces(APPLICATION_JSON)`, `@GET` returns `CommonDtos.Health(String status,
String version)` with `status = "ok"` and version from the build
(`@Value("${ca.version:dev}")`). Establishes "resources return DTO records."

**`common/HtmlEscaper`** — the escaping utility (stub with the real escape method;
used once UI renders JSON-driven HTML in later specs).

**Persistence config (`application.yml` + `application-dev.yml`).**
- H2 **file** DB at `jdbc:h2:file:./data/compute-admin;AUTO_SERVER=TRUE`.
- `spring.jpa.hibernate.ddl-auto=none`, `spring.jpa.open-in-view=false`.
- `spring.flyway.enabled=true`.
- Envers: `…envers.audit_table_suffix=_aud`, `…envers.store_data_at_delete=true`
  (no audited entities yet, so inert).
- `spring.config.import=optional:file:./.env[.properties]` for secrets.
- App config under the **`ca.*`** namespace.

**Baseline migration.** `db/migration/V1__baseline.sql` — comment-only baseline
so Flyway owns the schema from the first commit; real tables start at `V2`
(spec 003).

**Static shell.** `static/index.html` — minimal JSON-driven vanilla-JS shell that
calls `/api/health` and renders the result; no framework, no build step, no
server-side templating.

**Dev run recipe** (documented in the project `CLAUDE.md`, modelled on
birthday-rsvp's): `mvn -q spring-boot:run -Dspring-boot.run.profiles=dev`,
param'd by `PORT` (default 8080), ready when the log shows "Started Application".

**Tests.** `HealthWebTest` — `@SpringBootTest(webEnvironment = RANDOM_PORT)` +
`TestRestTemplate`, asserts `GET /api/health` returns 200 and `status=ok`. Proves
the RESTEasy-beside-Spring-Boot seam works end to end.

## Known Gaps

- **Authentication lands in spec 011**, which builds immediately after this
  skeleton and 002 (before the machine registry). The skeleton itself adds no
  auth; 011 adds Google login, per-user ownership, and the per-user MCP token.
- **Still local (project decision).** Network-bind hardening (loopback bind, TLS)
  and run rate-limiting remain tracked risks in ARCH.md, not built now.
- No CI, containerization, or packaging — local dev run only.

## Implementation Notes

Shipped as specified; the skeleton boots, serves `GET /api/health`, and serves the
static shell. Conventions from ARCH.md "Code conventions" are honoured: single
`service`-style layering (none needed yet), `HealthRS` is a `@Component @Path
@Produces(APPLICATION_JSON)` resource returning the `CommonDtos.Health` record,
`CommonDtos` is a `final` class with a private ctor, `JaxRsApplication` is the
empty `@Component @ApplicationPath("/api")` root, Flyway owns the schema
(`ddl-auto=none`, `V1__baseline.sql` comment-only), Envers is wired but inert, and
no `spring-boot-starter-validation` is on the classpath. String-UUID PKs and the
`ScopedValue` actor have no surface yet (no entities/requests) — they arrive with
their first consumers (003 / 011).

Where implementation went beyond the spec's letter:

- **Build version wiring.** Rather than leaving `ca.version` at its `@Value`
  default, `application.yml` sets `ca.version: '@project.version@'`, filtered by
  the Spring Boot parent's Maven resources plugin, so a packaged jar reports the
  real build version; the `@Value("${ca.version:dev}")` default still covers
  unfiltered IDE runs.
- **Hermetic web tests.** The spec's Tests section only named
  `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`. Added
  `src/test/resources/application-test.yml` (in-memory H2) and `@ActiveProfiles
  ("test")` on `HealthWebTest` so full-context tests never touch or create the
  file-based dev DB (`./data/compute-admin`). This is the pattern every later
  `*WebTest` inherits.
- **Concrete version pins.** Spring Boot is pinned to `3.5.16` (the "latest 3.5.x
  patch" the spec calls for, Java-25-capable Byte Buddy); the RESTEasy starter is
  pinned to `6.3.0.Final` via a property; `h2` is `runtime`-scoped and Lombok is
  `optional` with the Spring Boot plugin excluding it from the fat jar.

**Change division.** No `CONTRIBUTING.md` in the repo, so there is no
project-specific commit/PR policy to assess against. The work landed as four
`spec-001` commits on one branch (initial skeleton, then build-version wiring, test
DB isolation, and the Boot patch pin) with the `V1` migration travelling in the
skeleton commit alongside the code it backs — consistent with the cross-project
default in the global conventions.
