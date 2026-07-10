# compute-admin

MCP server + thin web UI to manage SSH-reachable machines via pre-approved
recipes and scripts. See [README.md](./README.md) for the overview and
[ARCH.md](./ARCH.md) for the target architecture, the gate enforcement points,
naming vocabulary, and the deferred-risk register (S1–S8). Use ARCH.md as the
benchmark when reviewing architectural fit.

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
