# Central integration results

Implemented on `feat/central-extension-results`, 2026-09-06. This is an
undeployed change, not a statement about the running production installation.
The database is not disposable: **do not run tests, start a test stack, or apply
the migration against it**. Test execution was stopped at the user's request.

## What the five steps deliver

1. **One inventory and finding workflow.** Registered scanners bind to existing
   assets. GitHub bindings also reference the native repository inventory using
   instance + numeric repository identity. Open observations project into
   `Vulnerability`, so existing asset access, ownership, exceptions and ageing
   apply; AI results remain distinct from Dependabot.
2. **Rich, retained evidence.** Findings retain descriptions, recommendations,
   source locations, confidence, engine/model, commit and issue/PR links.
   Validated PNG/JPEG/text/JSON evidence is stored locally and downloaded through
   asset-scoped authorization. Scan history exposes earlier evidence even after
   a finding changes or resolves.
3. **Explicit scan lifecycle.** Atomic, idempotent snapshots record SUCCESS,
   PARTIAL, FAILED and SKIPPED, including zero findings. Only a newer SUCCESS
   with `completeCoverage=true` closes missing findings, within its scanner and
   subject. Retries with identical content replay the acknowledgement; changed
   content under the same key and out-of-order snapshots return conflict.
4. **Ownership and controlled access.** Only ADMIN registers scanners/targets;
   only the assigned service user can submit, and it must also have access to
   the bound asset. Readers use the existing `AssetFilterService` boundary.
   Repository owner email maps to an existing SecMan username where possible;
   otherwise the registering administrator owns the created inventory asset.
5. **Central visibility and contract checks.** `/integrations` provides filters,
   evidence, coverage, run history and admin setup. GitHub repository details
   include an AI findings panel. Health events use existing chat subscriptions;
   the relay adds aggregate-only `integrations` with an ADMIN policy. Backend
   and Python clients share a versioned fixture and manual contract workflow.

## Safe rollout order

Local extension commits (not pushed):

- GitHub: `558fb12263739238db2a7e59a124dd8a945f3ec1` — 13 files covering client,
  configuration/CLI, orchestration/state, repository identity, docs and tests.
- Visual: `bbdaa93a20c8a1d0d6ba5f1cfb1d55eb3164c7fe` — 8 files covering client,
  CLI/plan, docs and tests; follows compatibility fix `e88305f`.

1. Back up production and rehearse V261 in a separately approved disposable
   environment before deployment. The migration adds five integration tables,
   backfills GitHub instance identity and replaces global repository uniqueness
   with instance-scoped uniqueness. It does not bulk-adopt or delete legacy
   findings. It has not been executed in this session.
2. Review/build the backend and frontend, then deploy them together through the
   normal release process. New clients must not be enabled against an old server.
3. In **Integration Results → Scanner settings**, register a scanner with source
   `GITHUB_AI` or `VISUAL`, a dedicated existing service user, and freshness hours.
   Registration is bounded to 100 scanners. Bind the actual inventory targets.
4. Give the service user access through the existing ownership/workgroup rules;
   registration itself does not grant asset access. Check that intended owners
   and workgroup members can see the inventory asset. Resolve ambiguous legacy
   inventory deliberately instead of guessing from repository display names.
5. Publish/release the separately committed extension changes after review, then
   configure `SECMAN_SCANNER_ID` (or the scanner-ID CLI flag). The existing
   `--push-to-secman` / `--secman-upload` remains the write trigger. The ID alone
   does not enable uploads. Existing login/token paths and legacy mode remain.
6. For visual MCP transport, grant `INTEGRATIONS_WRITE` (also sufficient for
   subject discovery) and supply `X-MCP-User-Email` for the assigned
   service user. Existing MCP permissions are not silently repurposed.
7. Subscribe to `INTEGRATION_SCAN_FAILED` and `INTEGRATION_STALE` where wanted.
   Health messages contain only generic status and an authenticated central-page
   link, never findings or asset identities. Staleness is checked every five
   minutes, with one notification attempt per scanner stale incident; delivery
   is best-effort, not an exactly-once durable alert queue.

Disable a scanner to stop ingestion without deleting history. Removing the
scanner ID switches a client back to its legacy mode; a rejected v1 request
never silently falls back. Do not reverse V261 or delete integration tables as
a rollback shortcut. Inventory/scanner foreign keys retain history and prevent
deleting referenced assets, repositories or service users; plan retention and
retirement before such deletion.

## API and safety limits

Base: `/api/integrations/v1`. Configuration uses `/scanners` and
`/scanners/{id}/subjects`; ingestion is `POST /runs`. Paginated reads use
`/runs`, `/findings`, and scanner subjects. `/runs/{id}` returns historical
findings/attachment metadata; `/findings/{id}` returns current detail;
`/findings/{id}/attachments/{attachmentId}` serves protected downloads.
`/summary` is asset-scoped. MCP exposes `submit_integration_run` and
`list_integration_subjects` through the same access/services.

Snapshots are limited to 500 findings; evidence is limited to 1 MiB per
attachment and 5 MiB combined textual/binary evidence per run. Oversized input
is rejected atomically, not silently truncated by the server. Pages are at most
100 records. Evidence URLs are references only: SecMan never fetches them.
Screenshots are decoded, bounded and re-encoded before storage.

The visual client always declares incomplete coverage because one page does not
prove coverage of its entire asset. GitHub High/Critical-only scans also remain
incomplete. Their missing findings therefore remain open. A future scanner can
claim complete coverage only when it actually scans the whole registered
subject without filtering, failures or truncation.

Legacy adoption is opt-in via exact `legacyIds`, on the bound asset, and only
for unclaimed `CLI_MANUAL` findings. Conflicting matches fail atomically.
Adoption preserves the existing vulnerability identifier/product scope and first
observation time so exceptions and ageing are not reset. No fuzzy bulk migration
is performed.

## Verification and release gates

Completed **before** the no-tests instruction: frontend build and 277 frontend
tests; visual extension suite 473 passing; an earlier backend compile succeeded.
GitHub focused suites passed 77 tests; its full run had 681 passed, four failed,
four skipped. The four failures came from old GitHub mocks lacking repository
IDs; after that compatibility fix, the focused GitHub suites passed 55 tests.
These results do not cover the final subsequent changes. The focused backend
test run was cancelled before test execution. Final lifecycle, persistence,
concurrency, authorization, notification and contract tests were written but
not run; full builds, runtime startup and E2E remain unverified. Do not treat
this branch as production-validated.

The manual `.github/workflows/integration-contract.yml` takes full published
40-character extension commit SHAs. It runs database-free unit/contract checks,
compares the shared fixture with each client's copy, and has no automatic push
or pull-request trigger. It has not been invoked. Locally, the opt-in entrypoint
is `bash scripts/check-integration-contract.sh --run`; do not invoke it without
fresh authorization to run tests. Real persistence/E2E tests additionally
require a confirmed disposable database and the normal stack lifecycle.

Static review found and corrected historical-evidence access, case-sensitive
Git identity, enterprise normalization, and immutable stored retries. No
remaining HIGH/CRITICAL issue was found in that review. Skill mirror check
passed. The OWASP gate still reports two BLOCK false positives: SHA-256 in
`IntegrationRunValidator.validate` hashes non-secret request content for replay,
and `IntegrationRunWriter.identifier` hashes external finding identity. Neither
hashes a credential. An independent reviewer confirmed this distinction; the
gate was not weakened and its exit status remains nonzero. Resolve the two
narrow policy exceptions through normal review before merge.

The gate's 15 REVIEW matches were adjudicated statically: read queries receive
asset IDs from `AssetFilterService`; MCP user lookup resolves the authenticated
delegation and reconstructs its persisted identity/roles, not a supplied asset
ID; authenticated reads are intentional while configuration is ADMIN-only and
writes require the assigned user plus asset access; URL parsing does not perform
outbound requests. Latest-batch queries now include every active integration
projection without displacing CrowdStrike rows. Non-replay committed submissions
request the existing coalesced materialized-view refresh.

OWASP review: A01 asset/assigned-user boundaries; A02 non-secret checksums only;
A03 static query templates/bound parameters and React text rendering; A04
bounded atomic snapshots; A05 protected attachment responses; A06 no new runtime
dependencies; A07 existing authentication; A08 validated evidence; A09 scoped
audit identifiers and generic health alerts; A10 no remote evidence fetch.

The relay and iOS snapshot schema remain version **2**. The app's existing
generic section renderer handles the additive aggregate section; no app update
or new credential path is required. iOS runtime behavior was not verified.

## Static finalizer record

- Scope: `origin/main` / `fd6205e8` through the current uncommitted root diff,
  plus the two independent extension commits above. Source and contract review
  only; not a comparison with deployed binaries.
- Runtime claims agree with build declarations: Kotlin 2.4.10/Micronaut 5.1
  (`build.gradle.kts`), Java25 (`src/backendng/build.gradle.kts`), Gradle9.7.0
  (`gradle/wrapper/gradle-wrapper.properties`), Astro7.2/React19
  (`src/frontend/package.json`), Picocli4.7.7 (`src/cli/build.gradle.kts`),
  Go1.24 (`src/relay/go.mod`), MariaDB11.4 (`docker/docker-compose.yml`).
  No major-version drift found. Authentication remains the HttpOnly cookie.
- Python legacy calls remain intact; new calls match `IntegrationController`,
  `IntegrationDtos`, and both MCP tools. Service-user permissions must still be
  configured by the operator. All three extension worktrees are clean; iOS
  remains unchanged at `820a4a0`.
- `CLAUDE.md`: 318 lines/4661 words → 319 lines/4691 words. Added the integration
  endpoint contract and updated its date. Existing three-entry changelog summary
  was already concise; no history or operational rules were removed.
- `./scripts/check-skill-sync.sh`: exit0, 29 matching files; no skills changed.
  `git diff --check`: clean. OWASP: exit1, two reviewed checksum false positives
  and 15 reviewed matches, as detailed above.
- Required but deliberately not performed: final build, startup, browser/E2E,
  real transaction/migration/concurrency and final client regression execution.
  The user's no-tests instruction supersedes those execution gates for this
  session; it does not turn them into passing release evidence.
