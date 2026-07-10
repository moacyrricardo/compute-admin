# 001 — Project skeleton

> Status: **doing** — branch `moacyrricardo/spec-001-project-skeleton`. Linear is
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
Boot **3.5.x**, Java **25**. Dependencies:
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
