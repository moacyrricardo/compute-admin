# 014 — Email + password authentication (replaces Google sign-in)

> **Status:** todo. Linear is BLOCKED for this repo, so no issue identifier;
> commits use `spec-014`. Supersedes the **authentication mechanism** of spec 011
> (Google sign-in): the JWT session, per-user MCP personal tokens, the pairing
> flow, `AuthContext`/`CurrentUser`/`Via`, `@Secured`, and the ownership→404 model
> from 011 are all **retained unchanged** — only the way a user proves identity and
> self-registers changes.

## Context

Spec 011 authenticated the UI with Google Sign-In (GIS): a Google ID token →
`AppUser` found-or-created by `googleSub` → app JWT. On a single local instance,
depending on Google as an external identity provider (a client-id to configure,
network verification, a real Google account per user) is friction with no benefit.
We drop Google entirely and use **self-service email + password** registration and
login.

The key finding from investigation: auth is cleanly layered — only the
*identity-proof* step is coupled to Google. Everything downstream of identity
(`JwtService`, `JwtScopeFilter`, `McpTokenAuthFilter`, `PersonalTokenService`,
`PairingService`, the MCP pairing flow, `AuthContext`/`CurrentUser`/`Via`,
`@Secured`, ownership→404) reads only `userId`/`email` and is agnostic to *how* the
user signed in. So this is a localized replacement of the identity-proof step, not a
re-architecture.

## Decision

Two public endpoints replace `POST /api/auth/google`:
- `POST /api/auth/register {email, password, name?}` — create an `AppUser`
  (bcrypt-hashed password), mint the app JWT, return `Session`. Duplicate email
  → **409**.
- `POST /api/auth/login {email, password}` — verify against the stored bcrypt hash,
  mint the JWT, return `Session`. Unknown email **or** wrong password → a single
  generic **401** with an identical message (existence never leaked, per the repo's
  404/401 rule).

Passwords are hashed with **BCrypt** (`spring-security-crypto`,
`BCryptPasswordEncoder`, strength 10); plaintext is never stored. Email is
normalized `trim().toLowerCase()` before store/lookup; uniqueness is enforced by the
existing `uq_app_user_email`. `JwtService` is unchanged (subject = `userId`, `email`
claim), so `JwtScopeFilter`, `@Secured`, the MCP token filter, the pairing flow, and
ownership all keep working untouched — pairing mints a token for whoever is signed
into the UI, regardless of sign-in method.

### Product decisions (locked)

| Decision | Choice |
|----------|--------|
| Password policy | min **8** chars, no complexity rules, cap **200** (so bcrypt's 72-byte truncation isn't surprising); validated by a guard clause in `AuthService` (manual validation, no Bean Validation). |
| Email verification | **None** (v1, single local instance). |
| Password reset | **Deferred** to backlog — operator resets via the DB; a future spec if it leaves local. |
| Login rate-limit / lockout | **None** — folds into S7 (now covering login attempts as well as runs). |
| Existing users | **Greenfield clean wipe** in the migration (no production users exist). |
| Email normalization | `trim().toLowerCase()` before store/lookup; uniqueness via `uq_app_user_email`; a pre-check returns a clean 409 rather than a raw constraint violation. |
| `name` field | Kept, **optional** at registration; defaults to the email local-part when omitted so `UserView.name` / the shell's `#user-name` stays populated. |

## Implementation

**pom.xml.** Add `org.springframework.security:spring-security-crypto` (version
managed by the Boot 3.5 parent — no explicit `<version>`). **Remove**
`com.google.api-client:google-api-client` and its version property. Do **not** add
`spring-boot-starter-security` — it would install a competing servlet filter chain
that fights the app's own `JwtScopeFilter`/`@Secured` model; `spring-security-crypto`
is a standalone crypto jar with no auto-config.

**`config`.** A `PasswordEncoder` `@Bean` (`new BCryptPasswordEncoder()`), injected
into `AuthService`.

**`auth/model/AppUser`.** Add `passwordHash`
(`@Column(name="password_hash", nullable=false, length=255)`). Remove `googleSub`
and the `uq_app_user_google_sub` unique constraint. `email` (unique, 320) stays;
`name` becomes optional.

**`auth/service/AuthService`.** Replace `loginWithGoogle` with:
- `register(email, password, name)` — validate (email non-blank + contains `@`;
  password length 8..200), normalize email, reject duplicates
  (`DuplicateEmailException` → 409), hash, save, mint JWT.
- `login(email, password)` — normalize, `findByEmail`, `passwordEncoder.matches`; on
  any miss throw `UnauthorizedException` (401) with a generic message.

Delete `GoogleIdTokenService`, `GoogleIdTokenServiceImpl`, `DevGoogleIdTokenService`.
Remove `AppUserRepository.findByGoogleSub`.

**`auth/api`.** `AuthRS`: replace `POST /google` with `POST /register` and
`POST /login` (both public, not `@Secured`). `AuthDtos`: replace `GoogleLogin` with
`RegisterRequest(email, password, name)` and `LoginRequest(email, password)`;
`Session`/`UserView` unchanged. New `DuplicateEmailException` in `auth/service` + a
`*ExceptionMapper` in `common/` returning `{"error":"email_taken"}` (409).

**Migration `V7__email_password_auth.sql`** (next free number; V1–V6 exist; Flyway
owns the schema, `ddl-auto=none`; H2 dialect). Greenfield wipe — delete
`personal_token` **before** `app_user` (FK `fk_personal_token_owner`), then
`pairing_request`, then reshape `app_user`:
```sql
-- spec-014: email+password auth replaces Google sign-in (revises spec-011 auth).
DELETE FROM personal_token;   -- FK -> app_user; must precede app_user rows
DELETE FROM pairing_request;
DELETE FROM app_user;

ALTER TABLE app_user DROP CONSTRAINT uq_app_user_google_sub;
ALTER TABLE app_user DROP COLUMN google_sub;
ALTER TABLE app_user ADD COLUMN password_hash VARCHAR(255) NOT NULL;
```

**UI (`static/app.js`, `static/index.html`).** Replace the Google button +
credential textarea + dev-bypass block in `showLogin()` with an email/password form
and Log in / Register actions posting to the new endpoints; on success,
`Session.set(token, user)` **exactly as today** (localStorage `ca.jwt`/`ca.user`
unchanged; `authHeaders()` unchanged). Surface 401/409 via the existing toast.

**ARCH.md / README.md / catalog (done as part of the build).**
- ARCH.md: Users & ownership + Authentication lines "Google sign-in" → "email +
  password"; S1 RESOLVED note reworded to name specs 011 **&** 014; S7 extended to
  note login attempts are unthrottled.
- README.md: Stack bullet "Google sign-in for the UI" → "Email+password sign-in";
  "See ARCH.md spec 011" → "specs 011 & 014".
- A WARNING banner on `specs/011-*.md` (added with this spec) points here; the
  catalog `specs/README.md` gets a 014 row and an 011 "auth superseded" note.
- Cosmetic Javadoc: `common/AuthContext.java` ("Google JWT") and
  `mcp/BeginSetupTool.java` ("Google sign-in").

## Known Gaps

- **No email verification** (v1, single local instance) — anyone who can reach the
  box can register. Revisit before non-local use.
- **No password reset flow** — operator resets via the DB. Backlog a spec if it
  leaves local.
- **No login rate-limiting / lockout** — folds into S7 (now covering login attempts
  as well as runs).
- **Password hashes / JWT secret / token hashes live unencrypted in local H2 /
  `.env`** — same boundary as S2 (unchanged from 011).

## Test plan

- `AuthServiceTest` — rewrite: `register_NewEmail_CreatesUserAndMintsJwt`,
  `login_CorrectPassword_ReturnsJwt`, `login_WrongPassword_Throws401`,
  `login_UnknownEmail_Throws401` (same body as wrong password),
  `register_DuplicateEmail_Throws409`, and **password-is-hashed**: reload the
  `AppUser` and assert `passwordHash` ≠ plaintext, `startsWith("$2")`, and
  `encoder.matches(plaintext, hash)`.
- `AuthWebTest` (new, `@SpringBootTest RANDOM_PORT`): register → JWT + user;
  duplicate register → 409; login happy → JWT; wrong password / unknown email → 401
  (identical body); a registered user's JWT authorizes a `@Secured` call (e.g.
  `GET /api/tokens` → 200).
- `OwnershipWebTest` — the `login(email)` helper posts `register` (or
  register-then-login); the rest (token isolation → 404, MCP with/without token) is
  unaffected and now implicitly proves the ownership + pairing model works for a
  password user.
- `AuthScopeTest` / `PairingServiceTest` — drop `setGoogleSub` from the user
  fixtures; filter/pairing assertions otherwise unchanged (pairing still mints a
  token for a password-registered signed-in user).
