# compute-admin

MCP server + thin web UI to manage SSH-reachable machines via pre-approved
recipes and scripts. See [README.md](./README.md) for the overview and
[ARCH.md](./ARCH.md) for the target architecture, the gate enforcement points,
naming vocabulary, and the deferred-risk register (S1–S8). Use ARCH.md as the
benchmark when reviewing architectural fit. [CONTRIBUTING.md](./CONTRIBUTING.md) is
authoritative for commit/PR conventions and code style.

Work is built one **spec** at a time under `specs/NNN-status-slug.md`
(`/new-spec` to author, the spec skills to implement).

## Spec workflow

```
Tracker:       linear (team BOL, title prefix `CA:`) — BLOCKED, see ## Linear
Branch prefix: moacyrricardo/spec-NNN-slug   # Linear blocked → fallback pattern
Specs dir:     specs/          # index is specs/catalog.md (lowercase)
```

- Commit subjects: `spec-NNN Short description` (no issue IDs while Linear is blocked).
- `CONTRIBUTING.md` is authoritative for commit/PR division and code style.
- API modules: **None** (see `## API Modules`) — finish-branch skips the API Diff.
- Version-bump policy: **none**. The app is unversioned (`0.0.1-SNAPSHOT` in `pom.xml`);
  shipping a spec never bumps it.

## Running (dev)

```bash
mvn -q spring-boot:run -Dspring-boot.run.profiles=dev
```

- Param'd by `PORT` (default `8080`).
- Ready when the log shows `Started Application`.
- Serves `GET /api/health` (JSON `{status, version}`) and the static UI shell at `/`.
- Uses the H2 **file** DB at `./data/compute-admin`; Flyway owns the schema.

## Dev server

```
Start: mvn -q spring-boot:run -Dspring-boot.run.profiles=dev
Port:  8080            # env PORT overrides
Ready: log line "Started Application"  (then GET /api/health returns {status, version})
```

Stop: kill the `spring-boot:run` process (Ctrl-C, or kill the PID / port 8080).

## Build & test

```
mvn -q test            # system mvn (no wrapper); Java 25, surefire + spring-boot-starter-test
```

Toolchain (mandatory): **Java 25** (`java.version` in `pom.xml`), system **Maven 3.8.7** —
there is no `mvnw` wrapper. Single-module build; `mvn -q test` covers the whole app.

## Dev-login (evidence capture)

The dev profile seeds **no** user (only the `demo` profile has `DemoSeeder`). Auth is
email+password → JWT sent as `Authorization: Bearer <token>`. Self-register a throwaway
user, then use the token:

```bash
TOKEN=$(curl -sX POST localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"dev@ca.local","password":"devpass123","name":"Dev"}' | jq -r .token)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/...
```

For UI flows, register once (above) then sign in through the login screen with the same
credentials.

## UI evidence

For live capture (`/spec-workflow:test-live`, the `live-verify:test-flow-headless` agent):

```
Base URL: http://localhost:8080/          # single page; hash router, no server-side routes
Auth:     localStorage "ca.jwt" = <JWT>, "ca.user" = <user JSON>   (see Dev-login above)
```

- **Seed auth without driving the login form:** register via `/api/auth/register` on the main
  thread, then in the browser set `localStorage.ca.jwt` / `ca.user` from that response and
  reload. Faster and less brittle than typing credentials; nothing to restore afterward
  (throwaway user, throwaway H2 file DB).
- **Routes** (`#/…`): `machines`, `machines/register`, `machines/<id>`, `monitor`, `runs`,
  `runs/<id>`, `machines/<mid>/recipes/<rid>/actions/<aid>` (approval screen) and `…/run`,
  `blueprints`, `blueprints/<id>`, `mcp`, `tokens`, `appkey`, `setup`. `#/` redirects to
  `#/machines`.
- **Restart the dev server for ANY front-end change.** `spring-boot:run` serves `app.js` from
  the `target/classes` copy made at startup, not live from the tree — a reload alone shows
  stale UI after a branch switch or edit.
- Image artifacts are published to the `verification-artifacts` orphan branch; it does not
  exist on `origin` yet, so the first capture creates it.

## SSH verify target (spec-003)

The registry and `SshExecutor` port are verified against the **real** MINA path,
not a mock. Spin up a throwaway sshd container and install the app's public key:

```bash
# 1. Boot the app once (dev profile) so it generates ./data/id_ed25519 and prints
#    the public key; fetch it authenticated from GET /api/ssh/public-key, or read
#    ./data/id_ed25519.pub.
PUBKEY="$(cat ./data/id_ed25519.pub)"

# 2. Throwaway sshd container with that key in authorized_keys, login user "admin".
docker run -d --name ca-sshd -p 2222:2222 \
  -e PUID=1000 -e PGID=1000 -e USER_NAME=admin \
  -e PUBLIC_KEY="$PUBKEY" \
  linuxserver/openssh-server

# 3. Register it and let the connectivity job (or a run) drive the real adapter:
#    host=127.0.0.1 port=2222 loginUser=admin  → status flips to ONLINE.
docker rm -f ca-sshd   # tear down when done
```

For fully offline work with no container, run under the `localssh` profile
(`LocalDevSshExecutor` runs argv as a local process instead of connecting).

## API Modules

**None.** compute-admin is a single deployable application (MCP server + web UI);
no module's compiled artifact is consumed as a library by another service, so
finish-branch closeouts skip the API Diff subsection.

## Linear

Title prefix **`CA:`** (team `BOL`).

**Linear is currently BLOCKED for this repo** — do **not** create or update Linear
issues for compute-admin work. Run `/new-spec` and the spec skills without the
Linear step: mark specs `doing`/`done` with **`spec-NNN`** commit subjects (not
`BOL-<n>`), and don't add an issue identifier to the spec. Revisit only when the
user unblocks Linear.
