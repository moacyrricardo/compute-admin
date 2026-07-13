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

**Users & ownership.** compute-admin is **multi-user with per-user isolation**: a
user signs in (email + password), registers **his own** machines, and adds **his own**
recipes; nothing is shared between users. `Machine` and `RecipeBlueprint` carry an
`owner`; `Recipe`/`Action`/`Run` derive ownership through their machine. Every
service scopes to the current user, and a not-owned or absent row reads as **404**
(existence is never leaked). The UI authenticates with an email+password-minted JWT; an
agent authenticates to MCP with a **per-user personal token**. This makes the
invariant enforceable: approval requires a UI-authenticated session (`via = UI`),
and the MCP token (`via = MCP`) has no path to it. See spec **011**.

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
- **Authentication:** Email+password sign-in → app JWT for the UI; per-user
  **personal token** for MCP (spec 011; auth mechanism now email+password per spec
  014). Per-user ownership on all data. Still a single
  **local** instance — transport hardening beyond auth stays deferred (S1').

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
| `cloud` | **Cloud** discovery: import machines (with cloud tags) from a provider account. `CloudProvider` port; impls `AwsCloudProvider`, `GcpCloudProvider`, `MagaluCloudProvider`. Read-only against the cloud. *(Target only — spec 009, parked; no `cloud` package exists yet.)* |
| `run` | Execution engine: async **jobs** (queued/running/done/failed), captured stdout/stderr + exit code, **live streaming** to UI (SSE) and MCP (progress). |
| `monitor` | Fleet monitoring read surface — enumerates approved MONITOR / app-monitor / app-ops actions into a per-(machine, app) rollup (host vitals + per-app checks + matched ops), served to the client-polled dashboard. Read-only; adds no gate. (`api`/`service` only.) |
| `ssh` | SSH adapter (MINA SSHD) behind the `SshExecutor` port; owns the **app keypair** lifecycle (generate on first boot, expose public key). |
| `mcp` | MCP tool/resource definitions. A **thin** adapter that maps tools onto the feature services — it holds **no business rules**, so the approval gate can't be bypassed by talking to MCP. |
| `auth` | Users, email+password sign-in, app JWT, per-user MCP **personal tokens**, the `AuthContext`/`CurrentUser` scope, `@Secured` filter. Owns the ownership rule everything else scopes by (spec 011; email+password sign-in per spec 014). |
| `audit` | Cross-cutting Envers infra: custom `AuditRevision` entity + `CurrentUserRevisionListener`. Not a feature module (no `*RS`/service/repository slices). |
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
   (enum/regex/allowed-set). Values are handled as **discrete argv elements**,
   never naively string-concatenated. Where a transport has no argv protocol (SSH
   `exec` runs one shell line), each element is POSIX single-quoted into that line
   so it stays one literal argument — the injection guarantee is the escaping, not
   the absence of a shell (see S4).

## Ports & contracts

| Port | Declared in | Implementations |
|------|-------------|-----------------|
| `SshExecutor` | `ssh` | `MinaSshExecutor` (real), `LocalDevSshExecutor` (dev, runs against localhost/no-op) |
| `RecipeDiscoverer` | `discovery` | one per service: `NginxDiscoverer`, `DockerDiscoverer`, `DatabaseDiscoverer`, `CronDiscoverer` |
| `CloudProvider` | `cloud` | `AwsCloudProvider` (first), `GcpCloudProvider`, `MagaluCloudProvider` |

`SshExecutor` also exposes `execStreaming(…, cancelKey)` + `cancel(cancelKey)` for
cancellable streamed runs — the channel-close seam behind run cancellation
(`RunStatus.STOPPED`, spec 026).

## Naming vocabulary

| Concept | Suffix | Example |
|---------|--------|---------|
| JAX-RS resource under `/api` | `*RS` | `MachineRS`, `RecipeRS`, `RunRS` |
| Business service | `*Service` | `ApprovalService`, `RunService` |
| Spring Data repository | `*Repository` | `MachineRepository` |
| JPA entity | (noun) | `Machine`, `Recipe`, `Action`, `Run` |
| DTO record bundle | `*Dtos` (nested records) | `RecipeDtos.ActionInput` |
| Read-model / view DTO | `*View` (nested in `*Dtos`) | `MonitorAppView`, `AppOpView`, `ChildRunView` |
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
  `discover_cloud(provider)` *(spec 009, parked — not yet implemented)*.
- **Run:** `run_action(machineId, actionId, params)` → `runId`; refuses if not
  `APPROVED` or params invalid. Streams output via MCP progress.
- **Resources:** the app's public SSH key (to install on targets); run output.

## Data model (sketch)

`Machine (1)─(N) Recipe (1)─(N) Action`; `Action (1)─(N) Run`.
`Machine (N)─(N) Tag`. Envers audits `Machine`, `Recipe`, `Action` (esp. the
approval transition) with `REV`/`REVEND`. `Run` is an append-only execution log
(not Envers-audited — it's already immutable history).

`Recipe` carries a mutable, **un-audited** `app_port_list` (JSON, the discovery
pre-fill of `(app-name, port)` pairs). `Run` supports **fan-out**: a parent `Run`
spawns one child `Run` per `(app-name, port)` item, linked by a self-referential
`parentRunId`. `RunStatus` includes **`STOPPED`** (a user-cancelled run — see the
`SshExecutor` cancel seam, spec 026).

## Additional rules

- JAX-RS resources **return DTO records** and throw mapped exceptions; they do
  **not** hand-build `Response` (except binary/stream endpoints and the SSE
  output stream).
- All hand-rendered HTML escaping goes through `common/HtmlEscaper`.
- No server-side templating engine — static shell + JSON-driven vanilla JS.
- New `RecipeType` values **`MONITOR`** (specs 022–025) and **`SYSTEMD`** (spec 026)
  are **display metadata only** — they classify recipes for the UI and do **not**
  change the gate; every one of their actions still requires UI approval to run.
- The acting caller is **ambient**: a filter binds a `ScopedValue<AuthContext>`
  (`userId`, `email`, `via`) read via the `CurrentUser` facade; the Envers
  listener records it. `/api` → `via = UI`, `/mcp` → `via = MCP`; a run records
  its `callerUserId` + `via` on the `Run` row.

## Code conventions

See [CONTRIBUTING.md](./CONTRIBUTING.md) for commit/PR conventions and code style.

---

## Security posture — deferred-risk register

Authentication and per-user ownership are now first-class (spec 011, resolving S1
and S8). What remains are deliberate simplifications for a single **local**
instance. Each item is tracked so it can be revisited before this is exposed
beyond a trusted local host. **Do not treat any `S#` as accepted forever — each
has a revisit trigger.** Rows resolved by a spec are struck through with a pointer,
not deleted.

| # | Decision (insecure for now) | Why it's OK today | Revisit trigger / hardening |
|---|-----------------------------|-------------------|-----------------------------|
| **S1** | ~~No authentication / authorization.~~ **RESOLVED by specs 011 & 014** — self-service email+password sign-in → app JWT (UI; Google sign-in in the original 011, replaced by email+password in 014), per-user personal token (MCP), per-user ownership on all data, not-owned → 404. The product is user-based, so this became a core feature, not a deferred risk. | — | — |
| **S1'** | **Transport still unhardened** — single local instance, default (non-loopback) bind, no TLS, no CSRF/Origin check on the JWT-authenticated UI. | Still one **local** instance; auth (S1) now enforces per-user isolation. | Before any non-local/shared/deployed use: bind `127.0.0.1`, add TLS, add Origin/CSRF checks on `/api`. |
| **S2** | **One app-owned private key, unencrypted on disk** (`./data/id_ed25519`, `chmod 600`), shared across **all** users' machines. Per-user isolation is logical (DB ownership), not cryptographic — the single key can reach any registered machine, so an ownership-scoping bug crosses the tenant boundary. | One local box; filesystem perms are the boundary; ownership checks are covered by tests (011). | Backups leaving the box or any deployment → encrypt at rest (passphrase/KMS envelope) and/or move to an agent. If per-tenant key isolation is ever needed, give each user their own keypair. |
| **S3** | **No SSH host-key verification** — the app accepts any target host key. | Speeds up onboarding; targets are ones you control on a LAN. | Any untrusted network path to a target → enable TOFU pinning (capture at registration, verify after; UI re-approve on change). The `machine` model should leave room for a pinned host-key column. |
| **S4** | **Typed params are bound into command templates.** Every param is an injection surface if validation is weak. | Params are enum/regex/allowed-set validated and kept as discrete argv; the SSH adapter, which has no argv protocol, POSIX single-quotes each element into the one shell line `exec` runs, so the guarantee is the escaping, not the absence of a shell. | Any free-form param, or a template whose structure (not just values) is caller-controlled, needs a stricter allowlist + a security review. Never add a "free-form command" param. |
| **S5** | **Per-action `sudo` assumes passwordless sudo** on the target for service ops. | You grant the login user scoped passwordless sudo out of band. | Prefer a narrow sudoers allowlist per command over blanket NOPASSWD; document what each machine's login user is allowed to escalate. |
| **S6** | **Cloud credentials** for discovery (AWS/GCP/Magalu) read from ambient env/instance profile. | Local dev uses your own already-present credentials. | Before multi-tenant or deployed use: scope to read-only `Describe*`/list permissions, store per-provider config as secrets, never in the DB in plaintext. |
| **S7** | **No rate limiting / concurrency cap** on runs, and (since spec 014) **no throttle / lockout on login attempts** — email+password sign-in accepts unlimited tries. A misbehaving agent could fan out many commands; an attacker could brute-force passwords. | Local instance, low volume; each user only drives his own machines. | Add a per-machine concurrency cap + a per-user/global run quota, and a login attempt throttle/lockout, before shared or higher-volume use. |
| **S8** | ~~MCP transport unauthenticated.~~ **RESOLVED by spec 011** — `/mcp` requires a per-user personal token; unauthenticated requests are rejected and every tool scopes to the token's user. | — | — |
| **S9** | **MCP infra exposure** — machine `host`/`port`/`loginUser` were surfaced over the MCP surface: `list_machines` returned host/port/loginUser and `register_machine` echoed host, pushing IP/hostnames, SSH login users, and ports into the LLM context (and any MCP-layer logging). Owner-scoped, so not a cross-user leak — needless infra/topology exposure. **RESOLVED by spec 028** — MCP identifies machines by `id` + user-provided `name` (+ `tags`/`status`) only; `host`/`port`/`loginUser` stay UI-only. `register_machine` still *accepts* a host as input but no longer echoes it. Row is open until 028 builds. | Operations already used the opaque `machineId`; the leak was the listing/registration **output**. | ~~Split the MCP-facing view from the UI view and add a friendly machine name~~ → **spec 028** (todo). |

When any trigger fires, open a spec to harden it and update this table (don't just
delete the row — record how it was resolved).
