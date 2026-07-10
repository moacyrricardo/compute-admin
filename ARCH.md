# Architecture

> Target architecture for compute-admin, written before implementation. Each
> feature lands as a numbered spec (`specs/`). Update this file as the
> architecture actually evolves. The benchmark for reviewing architectural fit.

## Purpose & core invariant

compute-admin exposes an **MCP server** and a **thin web UI** over a fleet of
SSH-reachable machines. It lets an agent or human run **pre-approved** operations.

**The core invariant — the reason the app exists:**

> Registration is open (MCP **or** UI). **Approval is UI-only.** MCP may run an
> action **only** if that action is in the `APPROVED` state, and only with
> parameters that validate against the action's declared schema.

Every architectural choice below serves that invariant. There is deliberately
**no MCP tool that approves** — approval flows through a REST endpoint that the
MCP tool layer never calls.

## Stack & framework posture

- **Spring Boot 3.5, Java 25.** Embedded Tomcat via `spring-boot-starter-web`,
  but **RESTEasy is the JAX-RS dispatcher** — mirrors the `birthday-rsvp`
  convention. Public API = JAX-RS `*RS` `@Component` resources under
  `@ApplicationPath("/api")`. **We do not write Spring MVC controllers.** Static
  UI assets are served by Spring Boot's default static-resource handler from
  `classpath:/static` (that handler is not an MVC controller and is fine).
- **MCP** over **HTTP/SSE** via the **MCP Java SDK**
  (`io.modelcontextprotocol.sdk:mcp`). The transport is
  `HttpServletSseServerTransportProvider` registered as a **`ServletRegistrationBean`**,
  so the MCP endpoint is a raw servlet beside RESTEasy — it never pulls in
  `spring-ai-starter-mcp-server-webmvc` and never touches Spring MVC. (If we later
  want the newer single-endpoint transport, swap in the Streamable-HTTP provider
  behind the same servlet seam.)
- **Persistence:** H2 **file** DB, Flyway migrations, Spring Data JPA, **Hibernate
  Envers** with the **validity audit strategy** (`REV` + `REVEND` columns on the
  audited config tables). Lombok, constructor injection throughout.
- **SSH:** **Apache MINA SSHD** client, behind a port (see below).
- **No authentication.** Local-only. Tracked as risk **S1**.

## Feature modules

Organized by feature module, each sliced into the same horizontal layers as
`birthday-rsvp` (`<module>/api|service|model|repository`). Two cross-cutting
packages (`common`, `config`) plus `audit` sit beside the modules. Base package
`com.iskeru.computeadmin`.

| Module | Responsibility |
|--------|----------------|
| `machine` | Machine registry: host/port/login-user, **tags**, connection status. No host-key store (see S3). |
| `recipe` | Recipes and their **actions**. An action is the runnable unit: command template + typed param schema + `sudo` flag + **approval state**. Approval logic lives here. |
| `discovery` | **Recipe** discovery: SSH into a known machine, detect installed services, **propose** recipes+actions (never mutates the box, never auto-approves). One `RecipeDiscoverer` port per service type. |
| `cloud` | **Cloud** discovery: import machines (with cloud tags) from a provider account. `CloudProvider` port; impls `AwsCloudProvider`, `GcpCloudProvider`, `MagaluCloudProvider`. Read-only against the cloud. |
| `run` | Execution engine: async **jobs** (queued/running/done/failed), captured stdout/stderr + exit code, **live streaming** to UI (SSE) and MCP (progress). |
| `ssh` | SSH adapter (MINA SSHD) behind the `SshExecutor` port; owns the **app keypair** lifecycle (generate on first boot, expose public key). |
| `mcp` | MCP tool/resource definitions. A **thin** adapter that maps tools onto the feature services — it holds **no business rules**, so the approval gate can't be bypassed by talking to MCP. |
| `audit` | Cross-cutting Envers infra: custom `AuditRevision` entity + `CurrentActorRevisionListener`. Not a feature module (no `*RS`/service/repository slices). |
| `common` | Cross-cutting: exception mappers, `HtmlEscaper`, `HealthRS`, JSON helpers. |
| `config` | Framework wiring: `JaxRsApplication` (`@ApplicationPath("/api")`), the MCP transport servlet registration, scheduling, the current-actor scope filter. |

## Dependency direction

- Within a module: `api → service → repository → model`; `model` depends on
  nothing and everything may depend on it.
- **Cross-module access goes through another module's *service* or a *port*
  interface — never another module's repository.** E.g. `run` calls
  `machine`'s lookup service and the `ssh` port, not `MachineRepository`.
- `ssh`, `cloud`, and `discovery` are **adapters behind ports**. Business code
  depends on the port (`SshExecutor`, `CloudProvider`, `RecipeDiscoverer`),
  never on MINA/AWS SDK types directly. Real-vs-dev is swapped by
  profile-scoped bean implementations, not conditionals in business code.
- **`mcp` depends only on feature *services*** (the same ones the REST layer
  uses). It must not reach repositories or re-implement any check — this is what
  keeps the gate un-bypassable.

## The gate — where it is enforced

1. **State machine.** An action is `DRAFT → PENDING_APPROVAL → APPROVED`
   (and `REVOKED`). Only `APPROVED` is runnable.
2. **Approval transition is reachable only from REST**, in a `recipe` service
   method that the `mcp` module never calls. There is no approve tool.
3. **Run path** (shared by UI and MCP) calls one `run` service entry point that:
   asserts the action is `APPROVED`, validates supplied params against the
   action's schema, binds them into the template, and only then hands the
   assembled command to `SshExecutor`. Unapproved → refused with a typed error.
4. **MCP visibility:** list tools return unapproved actions marked
   `pending_approval` (so an agent can ask a human to approve) but the run tool
   refuses them.
5. **Parameter safety:** templates use **named, typed, validated** params
   (enum/regex/allowed-set). Values are bound as **argv elements**, never string-
   concatenated into a shell line, to avoid injection (see S4).

## Ports & contracts

| Port | Declared in | Implementations |
|------|-------------|-----------------|
| `SshExecutor` | `ssh` | `MinaSshExecutor` (real), `LocalDevSshExecutor` (dev, runs against localhost/no-op) |
| `RecipeDiscoverer` | `discovery` | one per service: `NginxDiscoverer`, `DockerDiscoverer`, `DatabaseDiscoverer`, `CronDiscoverer` |
| `CloudProvider` | `cloud` | `AwsCloudProvider` (first), `GcpCloudProvider`, `MagaluCloudProvider` |

## Naming vocabulary

| Concept | Suffix | Example |
|---------|--------|---------|
| JAX-RS resource under `/api` | `*RS` | `MachineRS`, `RecipeRS`, `RunRS` |
| Business service | `*Service` | `ApprovalService`, `RunService` |
| Spring Data repository | `*Repository` | `MachineRepository` |
| JPA entity | (noun) | `Machine`, `Recipe`, `Action`, `Run` |
| DTO record bundle | `*Dtos` (nested records) | `RecipeDtos.ActionInput` |
| Domain exception + mapper | `*Exception` / `*ExceptionMapper` | `ActionNotApprovedException` |
| Port / adapter | `*Executor` / `*Provider` / `*Discoverer` | `SshExecutor` |
| MCP tool handler | `*Tool` | `RunActionTool`, `ListMachinesTool` |
| Scheduled task | `*Job` | `ConnectivityCheckJob` |

## MCP surface (initial)

- **Read:** `list_machines(tag?)`, `list_recipes(machineId)`,
  `list_actions(machineId, recipeId)` (includes `pending_approval`),
  `get_run(runId)`.
- **Create (allowed, never approves):** `register_machine`, `tag_machine`,
  `add_recipe`, `add_action`, `discover_recipes(machineId)`,
  `discover_cloud(provider)`.
- **Run:** `run_action(machineId, actionId, params)` → `runId`; refuses if not
  `APPROVED` or params invalid. Streams output via MCP progress.
- **Resources:** the app's public SSH key (to install on targets); run output.

## Data model (sketch)

`Machine (1)─(N) Recipe (1)─(N) Action`; `Action (1)─(N) Run`.
`Machine (N)─(N) Tag`. Envers audits `Machine`, `Recipe`, `Action` (esp. the
approval transition) with `REV`/`REVEND`. `Run` is an append-only execution log
(not Envers-audited — it's already immutable history).

## Additional rules

- JAX-RS resources **return DTO records** and throw mapped exceptions; they do
  **not** hand-build `Response` (except binary/stream endpoints and the SSE
  output stream).
- All hand-rendered HTML escaping goes through `common/HtmlEscaper`.
- No server-side templating engine — static shell + JSON-driven vanilla JS.
- The acting caller (UI vs MCP) is **ambient**: a filter/interceptor binds a
  `ScopedValue<Actor>` that the Envers revision listener and audit read via a
  `CurrentActor` facade. MCP calls resolve to actor `MCP`; a run's caller is
  recorded on the `Run` row.

---

## Security posture — deferred-risk register

The app is deliberately insecure in several ways to move fast on a **local-only**
tool. Each item is tracked so it can be revisited before this is ever exposed
beyond a trusted local host. **Do not treat any `S#` as accepted forever — each
has a revisit trigger.**

| # | Decision (insecure for now) | Why it's OK today | Revisit trigger / hardening |
|---|-----------------------------|-------------------|-----------------------------|
| **S1** | **No authentication / authorization** on UI or MCP. Anyone who reaches the port can register machines and run approved actions. | Runs only on `localhost` on a trusted dev box. | The moment it binds a non-loopback interface, is shared, or is deployed. Add auth (session/JWT) + bind to `127.0.0.1` by default. |
| **S2** | **App private key stored unencrypted on disk** (`./data/id_ed25519`, `chmod 600`). | Single-user local box; filesystem perms are the boundary. | Multi-user host, backups leaving the box, or any deployment → encrypt at rest (passphrase/KMS envelope) and/or move to an agent. |
| **S3** | **No SSH host-key verification** — the app accepts any target host key. | Speeds up onboarding; targets are ones you control on a LAN. | Any untrusted network path to a target → enable TOFU pinning (capture at registration, verify after; UI re-approve on change). The `machine` model should leave room for a pinned host-key column. |
| **S4** | **Typed params are bound into command templates.** Every param is an injection surface if validation is weak. | Params are enum/regex/allowed-set validated and bound as argv, not shell-concatenated. | Any template that must go through a shell, or free-form params, needs a stricter allowlist + a security review. Never add a "free-form command" param. |
| **S5** | **Per-action `sudo` assumes passwordless sudo** on the target for service ops. | You grant the login user scoped passwordless sudo out of band. | Prefer a narrow sudoers allowlist per command over blanket NOPASSWD; document what each machine's login user is allowed to escalate. |
| **S6** | **Cloud credentials** for discovery (AWS/GCP/Magalu) read from ambient env/instance profile. | Local dev uses your own already-present credentials. | Before multi-tenant or deployed use: scope to read-only `Describe*`/list permissions, store per-provider config as secrets, never in the DB in plaintext. |
| **S7** | **No rate limiting / concurrency cap** on runs. A misbehaving agent could fan out many commands. | Single user, single agent, low volume. | Add a per-machine concurrency cap + a global run quota when more than one agent can drive it. |
| **S8** | **MCP transport is unauthenticated HTTP/SSE** (no token). | Local, paired with S1. | When S1 is addressed, gate the MCP endpoint with the same auth (bearer token at minimum). |

When any trigger fires, open a spec to harden it and update this table (don't just
delete the row — record how it was resolved).
