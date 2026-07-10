# compute-admin

An MCP server (with a thin web UI) for managing a fleet of SSH-reachable machines
through **pre-approved recipes and scripts**. An AI agent — or a human — can list
machines, discover what runs on them, and execute approved operations (restart
nginx, add a site, run a custom `deploy.sh`, …). The catch that makes it safe to
hand to an agent: **execution is gated by a UI-only approval.** Anything can be
*registered* over MCP, but only a human clicking *approve* in the UI turns an
action into something MCP is allowed to run.

## What it does

- **Register machines** — an SSH-reachable host (host, port, login user), tagged
  with free-form labels. The app owns a single keypair; you install its public
  key into each box's `authorized_keys`.
- **Cloud import (discovery provider)** — pull instances (and their cloud tags)
  from a provider account to register machines in bulk. Providers: **AWS**, then
  **GCP** and **MagaluCloud**. Import never mutates the cloud side.
- **Add recipes** — a recipe is a named bundle of **actions** on a machine.
  - *Built-in* recipe types: **nginx, docker, database (mysql/mariadb/postgres),
    cron.** Their actions are auto-discovered by SSHing into the box and
    **proposing** recipes + default actions (nothing runs, nothing is approved
    until you say so).
  - *Custom* recipes: your own script on the box (e.g.
    `/home/ec2-user/app/minhabufunfa/run.sh`) wrapped as an action.
- **Approve, then run** — an action is a command template with **typed,
  validated parameters** and an optional per-action `sudo` flag. Once approved in
  the UI, it can be run. Runs are **asynchronous jobs** with **live-streamed**
  output (stdout/stderr) and a recorded exit code.
- **Full audit** — every approval, config change, and each run (who/what/when/
  exit code, MCP vs UI) is recorded.

## Trust model in one paragraph

Creating machines and recipes is allowed from **either** MCP or the UI. **Approval
is UI-only** — there is no MCP tool that approves. MCP can *see* unapproved
actions (marked `pending_approval`) so an agent knows they exist and can ask you
to approve them, but attempting to run one is refused. MCP can run **only**
approved actions, with parameters validated against the action's declared schema.
See [ARCH.md](./ARCH.md) for the enforcement points and the **deferred-risk
register** of everything currently left insecure on purpose.

## Stack

- **Spring Boot 3.5 / Java 25.** JAX-RS via **RESTEasy** (the API is `*RS`
  `@Component` resources under `/api`); **no Spring Web MVC controllers**. UI is a
  static HTML + vanilla-JS shell rendered from JSON.
- **MCP server** over **HTTP/SSE**, wired with the **MCP Java SDK** as a plain
  servlet (kept off Spring MVC, sitting beside RESTEasy on the same Tomcat).
- **H2 file** database, **Flyway** migrations, **JPA + Hibernate Envers**
  (validity strategy) for audit, **Lombok**.
- **SSH** via **Apache MINA SSHD**; a single app-owned **ed25519** keypair.
- **No authentication — local-only for now** (tracked as a deferred risk).

## Status

Greenfield. Architecture is specified in [ARCH.md](./ARCH.md); features are built
one **spec** at a time (`specs/NNN-status-slug.md`, created with `/new-spec` and
implemented with the spec skills). Nothing is built yet.

## Running (once the skeleton spec lands)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Dev uses the H2 file DB; the app prints its public SSH key on first boot for you
to install on target machines. Concrete run/verify recipes will live in
`CLAUDE.md` once the first spec is done.
