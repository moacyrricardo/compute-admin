# 048 — Release pipeline (tagged uber-jar + download docs)

**Status:** done · branch `moacyrricardo/spec-048-release-pipeline` · no Linear issue
(blocked; tracked as `spec-048`). Graduated from concern
[045](./045-todo-arch-cleanups.md) §4.

## Context

`spring-boot-maven-plugin` is already configured, so `mvn package` **already** produces an
executable fat/uber jar (`target/compute-admin-*.jar`, runnable with `java -jar`). What's
missing is distribution: the only workflow is `.github/workflows/tests.yml`, and the README
has no download / `java -jar` / release instructions. Highest external value of the 045
cleanups — it's what stands between the project and a one-command download.

## Decision

Add a **tag-triggered GitHub Release** workflow that builds the jar and publishes it (with a
SHA-256 checksum) as a Release asset, give the jar a **stable filename**, and add a README
**"Download & run"** section.

## Implementation

- **`pom.xml`:** set `<build><finalName>compute-admin</finalName>` so the asset is
  `compute-admin.jar` (stable, version-independent name). The Release itself carries the
  version (the git tag).
- **`.github/workflows/release.yml`:**
  - Trigger: `on: push: tags: ['v*']` plus `workflow_dispatch` (manual re-run).
  - `permissions: contents: write` (to create the release).
  - JDK 25 temurin + maven cache (mirror `tests.yml`); `mvn -B -ntp package -DskipTests`
    (tests already gate every push via `tests.yml`; keep the release fast — or run tests,
    decide in review).
  - Compute `sha256sum target/compute-admin.jar > compute-admin.jar.sha256`.
  - Publish with `softprops/action-gh-release@v2` (or `gh release create "$GITHUB_REF_NAME"`),
    attaching `target/compute-admin.jar` + `compute-admin.jar.sha256`, release name = the tag.
  - Concurrency-guard by tag (mirror `tests.yml`).
- **README "## Download & run":**
  - Grab `compute-admin.jar` from the latest [Release](../../releases); verify the checksum.
  - `java -jar compute-admin.jar` — ready when the log shows `Started Application`.
  - Runtime knobs: `PORT` (default 8080), `--spring.profiles.active=<profile>`, the H2 file
    DB at `./data/compute-admin` (Flyway owns the schema), `GET /api/health`. Point at the
    existing "Running (dev)" section for source builds; this section is the binary path.

## Known Gaps

- **No signing / notarization / SBOM** — a checksum only. Add later if distribution widens.
- **No container image** — a `Dockerfile` + GHCR publish is a natural follow-up, out of scope
  here (jar-only).
- Releases fire only on `v*` tags (+ manual dispatch), not on every push — intentional.
- Pom `version` stays `-SNAPSHOT` (informational); the *release* version is the tag. If a
  matching pom version is wanted, set it at tag time (deferred).

## How the implementation differed

Faithful to the decision. Notable resolutions of the spec's open choices:

- **Tests in the release build:** skipped (`mvn -B -ntp package -DskipTests`), as the
  spec's leaning suggested — `tests.yml` already gates every push, so the release stays
  fast.
- **Publish mechanism:** used `softprops/action-gh-release@v2` (the spec's first option)
  rather than the `gh release create` alternative; release name = `${{ github.ref_name }}`.
- No change to the pom `version` (stays `-SNAPSHOT`, per Known Gaps). No signing/SBOM/
  container image (out of scope).

## Related

concern [045](./045-todo-arch-cleanups.md) (§4), `.github/workflows/tests.yml` (spec CI,
PR #52), the `demo/` harness README (overlapping run docs), CONTRIBUTING.md.
