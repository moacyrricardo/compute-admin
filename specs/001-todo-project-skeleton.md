# 001 — Project skeleton

## Context

compute-admin is greenfield. Before any feature can land we need a buildable,
runnable Spring Boot base that fixes the framework posture ARCH.md commits to:
RESTEasy for JAX-RS (no Spring MVC controllers), H2 file DB, Flyway, JPA +
Hibernate Envers, Lombok. Every later spec assumes this skeleton exists.

## Decision

Stand up the Maven project and the minimum wiring to boot, serve `/api/health`,
and serve a static UI shell — nothing domain-specific yet.

- **Build:** Spring Boot 3.5, Java 25. Embedded Tomcat via
  `spring-boot-starter-web`, but **RESTEasy is the JAX-RS dispatcher**. No Spring
  MVC controllers anywhere.
- **Base package:** `com.iskeru.computeadmin`. Package layout is by feature
  module then horizontal layer (`<module>/api|service|model|repository`), with
  `common`, `config`, and `audit` beside the modules (see ARCH.md).
- **Persistence:** H2 **file** DB under `./data`, Flyway migrations, Spring Data
  JPA, Hibernate Envers configured (validity strategy) but with no audited
  entities yet. Constructor injection throughout.

## Implementation

- `pom.xml`: `spring-boot-starter-web`, RESTEasy (JAX-RS impl + Spring Boot
  integration), `com.h2database:h2`, `org.flywaydb:flyway-core`,
  `spring-boot-starter-data-jpa`, `org.hibernate.orm:hibernate-envers`, Lombok.
- `config/JaxRsApplication` — `@ApplicationPath("/api")`, registers `*RS`
  resources. RESTEasy runs as a servlet/filter beside Spring Boot; the default
  static-resource handler (not an MVC controller) serves `classpath:/static`.
- `common/HealthRS` — `GET /api/health` returns a small DTO record
  (`{status, version}`); establishes the "resources return DTO records" rule.
- `common/HtmlEscaper` — stub utility (used once UI renders JSON-driven HTML).
- `db/migration/V1__baseline.sql` — empty baseline so Flyway owns the schema from
  commit one.
- `application.yml` + `application-dev.yml` — H2 file URL, Flyway on, Envers
  properties. Dev profile documented for `mvn spring-boot:run
  -Dspring-boot.run.profiles=dev`.
- `static/index.html` — placeholder shell that calls `/api/health` to prove the
  JSON-driven vanilla-JS approach.

## Known Gaps

- **No authentication and no network-bind hardening** in this spec. ARCH.md S1
  frames auth as deferred and lists "bind to `127.0.0.1` by default" as the S1
  hardening — but there is currently **no spec** that owns either. Track a
  dedicated security spec before the run path (005) and MCP write tools (008)
  are exposed; until then the skeleton inherits Spring Boot's default bind.
- No CI, containerization, or packaging — local dev run only.
