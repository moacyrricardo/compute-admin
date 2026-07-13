# compute-admin

MCP server + thin web UI to manage SSH-reachable machines via pre-approved
recipes and scripts. See [README.md](./README.md) for the overview and
[ARCH.md](./ARCH.md) for the target architecture, the gate enforcement points,
naming vocabulary, and the deferred-risk register (S1–S8). Use ARCH.md as the
benchmark when reviewing architectural fit. [CONTRIBUTING.md](./CONTRIBUTING.md) is
authoritative for commit/PR conventions and code style.

Work is built one **spec** at a time under `specs/NNN-status-slug.md`
(`/new-spec` to author, the spec skills to implement).

## Running (dev)

```bash
mvn -q spring-boot:run -Dspring-boot.run.profiles=dev
```

- Param'd by `PORT` (default `8080`).
- Ready when the log shows `Started Application`.
- Serves `GET /api/health` (JSON `{status, version}`) and the static UI shell at `/`.
- Uses the H2 **file** DB at `./data/compute-admin`; Flyway owns the schema.

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
