# 011 — User accounts, authentication & ownership

## Context

compute-admin is **user-based**: a user signs in, registers **his own**
machines, and adds **his own** recipes; nothing is shared between users. That
turns authentication + per-user ownership from a deferred risk (S1) into a
**foundational feature** — "his machines" is undefinable without user identity —
so this spec lands **before** the machine registry (003) and every owned entity
carries an owner from its first migration. The model is birthday-rsvp's, applied
directly (Google login, JWT, `CurrentUser` facade, `@Secured`, ownership→404).
The one net-new piece is per-user **MCP authentication**.

This spec **supersedes the placeholder** actor scope from 002:
`ScopedValue<Actor>` → `ScopedValue<AuthContext>`, `CurrentActor` → `CurrentUser`,
`Actor` → `Via`, `ActorScopeFilter` → the two auth filters below.

## Decision

Google sign-in mints an app JWT for the UI. An agent authenticates to MCP with a
**per-user personal token** the user generates in the UI. Both resolve to an
`AuthContext(userId, email, via)`. Every owned entity has an `owner`; every
service scopes reads/writes to `CurrentUser.require()`; a not-owned or absent row
reads as that entity's **404** (existence is never leaked — birthday-rsvp rule),
never 403. Approval remains UI-only and is now enforceable: it requires a
UI-authenticated session, and a user may only approve **his own** actions.

## Implementation

**`auth/model`.**
- `AppUser` — `String id = UUID.randomUUID()`; `email` (unique); `name`;
  `googleSub` (Google subject, unique); `Instant createdAt`. Not Envers-audited.
- `PersonalToken` — `String id`; `@ManyToOne AppUser owner`; `label`;
  `tokenHash` (store a **hash**, never the plaintext); `Instant createdAt`,
  `lastUsedAt` (nullable), `revokedAt` (nullable).

**`auth/repository`.** `AppUserRepository` (`findByGoogleSub`, `findByEmail`);
`PersonalTokenRepository` (`findByTokenHashAndRevokedAtIsNull`,
`findByOwnerId`).

**`auth/service`.**
- `GoogleIdTokenService` **port** → `GoogleIdentity(sub, email, name)` from a
  Google ID token. Real `GoogleIdTokenServiceImpl` (verifies against Google);
  `DevGoogleIdTokenService` (`dev` profile) trusts a raw email as the credential
  — the birthday-rsvp dev bypass. Swapped by profile-scoped beans.
- `AuthService.loginWithGoogle(idToken)` → verify → **find-or-create** `AppUser`
  by `googleSub` (first login self-registers) → mint app JWT via `JwtService`.
- `JwtService` — mint/verify the app JWT (subject = `userId`, `email` claim);
  secret from `ca.auth.jwt-secret` (via `.env`).
- `PersonalTokenService` — `create(label)` returns the **plaintext once** (stores
  only its hash), `list()` (metadata), `revoke(id)`. All scoped to the current
  user.

**Actor context (`common`).**
- `AuthContext(String userId, String email, Via via)`; `Via` enum `UI | MCP |
  SYSTEM`.
- `CurrentUser` facade over a package-private `ScopedValue<AuthContext>`:
  `require()` (throws 401 if unbound), `optional()`, `userId()`, `email()`,
  `via()`, `userIdOrSystem()`.

**Auth entry points (one filter per surface, mirroring birthday-rsvp).**
- `config/JwtScopeFilter` on `/api/*` — validates `Authorization: Bearer <jwt>`,
  binds `AuthContext(userId, email, UI)`. The one place JWTs are validated.
- `config/McpTokenAuthFilter` on `/mcp/*` — validates the personal token
  (bearer), looks it up by hash (rejecting revoked), touches `lastUsedAt`, binds
  `AuthContext(userId, email, MCP)`. An unauthenticated MCP request is **rejected**
  (resolves S8).
- Registered via `FilterRegistrationBean`s (plain beans, path-scoped), per the
  convention.
- `@Secured` name-binding + `AuthFilter` (`@Provider @Secured`) aborts 401 when
  unbound. Public (not `@Secured`): `POST /api/auth/google`, `GET /api/health`.
- Scheduled jobs run unbound → `via = SYSTEM`, `userId` null; the Envers listener
  (`CurrentUserRevisionListener`, née `CurrentActorRevisionListener`) records
  `userIdOrSystem()` + `via`.

**`auth/api`.**
- `AuthRS` (`@Path("/auth")`, public) — `POST /google` `{credential}` →
  `AuthDtos.Session(token, UserView)`.
- `TokenRS` (`@Path("/tokens")`, `@Secured`) — `POST /` create (returns plaintext
  once), `GET /` list, `DELETE /{id}` revoke. Current user only.

**Ownership rule (applied by the retrofit in 003/004/005/010).**
- `Machine` and `RecipeBlueprint` carry `@ManyToOne AppUser owner`. `Recipe`,
  `Action`, and `Run` derive ownership through their `Machine`.
- Every service resolves via `CurrentUser.require()` and filters by owner; a
  cross-user or missing id throws the entity's `*NotFoundException` (404).
- A user may `instantiate` a blueprint only onto **his own** machines.

**Migration `V2__auth.sql`** — `app_user`, `personal_token`. (Owned-entity owner
columns are added in each entity's own migration: 003 `V3`, 004 `V4`, 005 `V5`,
010 `V6`.)

**Tests.**
- `AuthServiceTest` — Google login self-registers a new user, returns an existing
  one on second login; JWT round-trips.
- `PersonalTokenServiceTest` — create returns plaintext once + stores only a
  hash; revoke invalidates; lookup rejects revoked.
- `OwnershipWebTest` (`@SpringBootTest RANDOM_PORT`) — user A cannot see or act on
  user B's machine/recipe/run (**404**, not 403); MCP with A's token scopes to A;
  MCP without a token is rejected. `DevGoogleIdTokenService` mints per-user JWTs
  (distinct email per user).

## Known Gaps

- **Still local (project decision).** One instance, now multi-user and
  authenticated. Loopback-bind, run rate-limiting (S7), and general transport
  hardening remain **tracked** risks, not built here — revisit if it ever leaves
  the local box.
- **JWT secret + personal-token hashes live in the local H2 DB / `.env`**,
  unencrypted at rest (same boundary as S2). Fine for a single local box; revisit
  before any deployment.
- Personal tokens are long-lived until revoked (no expiry/rotation in v1).
