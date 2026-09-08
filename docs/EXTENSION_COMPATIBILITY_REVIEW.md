# Extension compatibility and central reporting review

Reviewed 2026-09-06 against local SecMan `fd6205e8`, GitHub scanner `b630c48`,
visual scanner `bdf1f67` plus fix commit `e88305f`, and iOS app `820a4a0`.
**Implementation follow-up:** the five central-result changes are now implemented
locally; see [Central integration results](INTEGRATION_RESULTS.md) for the API,
rollout order, verification limits and remaining release gates. The original
review below is retained as the baseline assessment.

This is a source-contract review of these checkouts, not confirmation of the
versions deployed in production. No production imports, emails or deletions
were performed. The assessment below describes the original baseline, before
the implementation follow-up.

## Assessment

The existing upload paths remain usable, but SecMan does not yet retain or
present all extension results. In particular, the AI GitHub scanner and SecMan's
native GitHub/Dependabot inventory represent the same repository separately.
Fixing transport compatibility alone cannot make SecMan the central system of
record for these findings.

| Extension | Contract assessment | Changes made |
| --- | --- | --- |
| `secman_ai_github` | Login cookie extraction and bearer upload match the current API; request fields still match. | None required for the checked API contract. Integration gaps below remain. |
| `secman_visual_check` | REST and MCP paths and payloads match. Existing-ID lookup had drifted from the new installer filtering behavior. Unbounded category labels could exceed the database ID limit. | Include installer findings in REST/MCP deduplication reads; follow MCP `totalPages` across empty pages; bound IDs to 255 characters while retaining their digest; add regression tests and correct upload documentation. |
| `secman_app_ios` | Relay paths, signing inputs, envelope version 2 and the six section payloads match. Unknown sections have a generic renderer. | No wire-contract change required. Full app/runtime verification remains outstanding. |

## Checked contracts

| Client operation | Current contract and permission requirement |
| --- | --- |
| Both scanners: login | `POST /api/auth/login`, `username`/`password`; JWT in `Set-Cookie: secman_auth`, not JSON. GitHub sends bearer thereafter; visual retains the HTTP cookie. MFA requires a separate interactive flow or existing token. |
| Both scanners: upload | `POST /api/vulnerabilities/cli-add`, ADMIN or VULN; `hostname`, `cve`, `criticality`, `daysOpen`, optional `owner`; response includes `success`, `operation`, asset and vulnerability IDs. |
| Visual: existing IDs | `GET /api/vulnerabilities/current`, ADMIN/VULN/SECCHAMPION with asset scoping; reads `content`, `assetName`, `vulnerabilityId`, `hasNext`. Now explicitly includes exceptions and installer findings. |
| Visual: REST inventory | `PUT /api/assets/import`, ADMIN; `name`, `type`, `owner`, `uri`; reads `created` and `asset.id`. This is an upsert. |
| Visual: MCP | `POST /mcp`; API key plus `X-MCP-User-Email`. `get_vulnerabilities` reads wrapped JSON with `vulnerabilities` and `totalPages`; `add_vulnerability` reads `vulnerabilityCreated`; `create_asset` reads its message and treats duplicate rejection as already registered. |
| Visual: MCP permissions | Both LISTING and CALLING map `get_vulnerabilities` and, historically, `add_vulnerability` to `VULNERABILITIES_READ`; add also enforces ADMIN/VULN on the delegated user. Registration needs `ASSETS_WRITE`. Actual deployed service-account roles were not inspected. |
| iOS: relay | `/api/v1/providers`, nonce/OIDC/GitHub/enrollment/device challenge/token flows, `/session`, `/meta`, `/status`; `RELAY_SNAPSHOT_SCHEMA_VERSION` and `relaySupportedSnapshotSchemaVersion` are both 2. Six sections: totals, KPIs, exceptions, imports, top products, top servers. |

Evidence: [request DTO](../src/backendng/src/main/kotlin/com/secman/dto/AddVulnerabilityRequestDto.kt),
[vulnerability controller](../src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityManagementController.kt),
[asset controller](../src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt),
[MCP permissions](../src/backendng/src/main/kotlin/com/secman/mcp/McpToolPermissions.kt),
[MCP paging/filtering](../src/backendng/src/main/kotlin/com/secman/mcp/tools/GetVulnerabilitiesTool.kt),
[relay API](../src/relay/internal/api/api.go),
[snapshot builder](../src/backendng/src/main/kotlin/com/secman/relay/RelaySnapshotBuilder.kt).

## Recommended SecMan changes, in priority order

### 1. Join AI findings to the existing GitHub repository inventory

The extension's `secman_push.push_records` sends `rec.full_name` as `hostname`.
`VulnerabilityService.addVulnerabilityFromCliLocked` creates an `Asset` of type
`SERVER` when that name is absent. SecMan's native GitHub import instead writes
`GithubRepository`, `GithubRepoDependabotAlert` and `GithubRepoFindingSnapshot`.
`GithubRepositoryController` and `GithubRepoAlertService` use those tables, so
AI findings do not contribute to their repository summaries or owner alerts.

Add an explicit relationship between findings and the existing repository
inventory. Use GitHub instance plus stable repository ID for identity, retaining
owner/name and repository URL as display fields. This also avoids conflating
identically named repositories on different enterprise hosts and handles renames.
Keep separate scanner observations when correlating Dependabot and AI results;
do not simply add potentially overlapping counts.

Acceptance: an AI finding appears on the same repository detail page as its
Dependabot findings, has the intended owner, and participates in a clearly
defined repository summary/notification policy. Renaming a repository preserves
its finding history.

### 2. Add a versioned finding ingestion contract with evidence

The current add DTO accepts only five fields. Both scanners discard rich context
at that boundary: title, description, recommendation, file/line or page URL,
evidence, confidence, engine/model, commit and links to issues/fix PRs. The visual
scanner's screenshot, HTTP status/redirect/checksum data and review flag also
stay in its reports or separate database. Optional asset registration stores a
URI, but cannot represent evidence for multiple pages on the same host.

Introduce a small versioned ingestion API backed by a shared service also used
by MCP: source/scanner instance, stable external finding ID, subject reference,
severity, title, description, recommendation, first/last observation times and
source-specific evidence. Preserve informational observations as INFO instead
of coercing them to LOW. Store attachments behind the existing asset/repository
authorization boundary with size/type validation; render untrusted text safely.
Keep `cli-add` supported as a legacy adapter.

Acceptance: a responder can understand, verify and remediate a finding from
SecMan without needing access to the scanner's local output directory.

### 3. Make provenance and scan lifecycle explicit

New extension rows are labeled `CLI_MANUAL` by `VulnerabilityService`, so SecMan
cannot reliably separate the two scanners or individual scanner installations.
Neither uploader declares a complete scan scope or reconciles findings missing
from a successful later scan. A failed scan and a clean scan cannot be
distinguished centrally. The visual scanner skips known IDs by default, so
severity and observation freshness do not update unless
`--secman-allow-existing` is used.

Use source/scanner/subject/external-ID identity and a scan-run record with
start/finish times, scope, success/partial/failure, coverage, and accepted/rejected
counts. Only a successfully completed scan of an explicit scope may resolve
missing findings from that scanner. Failed, partial, skipped or threshold-filtered
scans must not imply remediation. Separate OPEN/RESOLVED state from exceptions
and operator verification.

Preserve `firstSeenAt` as the SLA anchor. GitHub's current fingerprint includes
severity and title (`secscan.findings.fingerprint`), so changing either creates a
new finding ID; migrate identities deliberately rather than silently changing
the hash and duplicating production history. GitHub `daysOpen` also depends on
its issue ledger, not an independent finding observation history.

Acceptance: retries are idempotent; a severity change updates one finding; a
failed scan never closes findings; one scanner cannot resolve another's results.

### 4. Route findings to owners and reuse central workflows

GitHub uploads omit owner, so new assets get `CLI-IMPORT`. Visual defaults to
`secman-visual-check`; these labels are not necessarily real users. Neither
upload carries workgroup mappings. The generic CLI auto-create path does not
record the delegated user as creator/uploader, whereas MCP `create_asset` does.
Consequently a write can succeed without making the result discoverable by the
intended normal user. Native GitHub owner-email mappings also do not attach to
the synthetic asset created by the AI extension.

Resolve subjects and ownership server-side using existing inventory mappings;
apply the established asset/repository access rules to ingestion and reads.
Add narrowly scoped integration permissions (including explicit vulnerability
write permission) with a documented migration for existing MCP keys. Reuse
central exception, assignment, SLA and notification services. Do not grant
scanners ADMIN merely to work around missing integration capabilities.

Acceptance: the responsible user sees and can act on the finding, an unrelated
user cannot, and an integration key can only submit within its assigned scope.

### 5. Report scanner coverage and freshness alongside finding counts

The relay `imports` section currently exposes only CrowdStrike. Aggregate
vulnerability counts may include extension rows but cannot explain which
scanner ran, whether it failed, which targets were clean, or how old its evidence
is. GitHub scans with zero High/Critical findings create no asset through the
current push path. Visual registration of clean hosts is optional.

Add a central integrations page and dashboard filters for source, subject,
owner, severity, state and last successful scan. Show covered/failed/unscanned
targets and stale integrations. Publish compact scan-run summaries through the
relay using its role policies; the iOS generic renderer already supports new
sections. Add an event to the existing chat notification framework for failed
or stale integrations, with actionable links to the central run record.

Acceptance: "zero findings" is visibly different from "not scanned" and
"scanner failed" in both the web dashboard and authorized relay views.

### 6. Protect contracts in CI and make ingestion scale

Root builds do not exercise the gitignored extension repositories. Add CI jobs
that check out pinned extension revisions and run shared request/response
fixtures plus disposable-instance round trips: login, import, readback, retry,
severity change, exceptions, pagination, authorization negatives and resolution.
Publish supported API/schema versions and update clients alongside changes.

Move MCP exception/installer filters into SQL so page contents and total counts
agree. `GetVulnerabilitiesTool` currently filters after paging. Replace the
per-finding CLI path's synchronization of every other vulnerability's
`importTimestamp` with explicit scan-run/source freshness; it performs growing
work on each upload and makes unrelated findings appear freshly imported.
Add bounded batch ingestion and per-item acknowledgements.

Acceptance: CI detects missing fields/changed roles before deployment; batch
imports preserve source-specific timestamps and do not repeatedly rewrite all
other rows on the same asset.

## Existing integration protections worth preserving

- CrowdStrike replacement now deletes by source (and requested severities),
  preserving newly created `CLI_MANUAL` extension findings. Evidence:
  `CrowdStrikeVulnerabilityImportService.importVulnerabilitiesForServer` scoped
  deletion and `VulnerabilitySources`. Do not return to unscoped asset deletion.
- The CLI upsert preserves an existing `firstSeenAt` anchor, and scanner IDs are
  namespaced (`SECSCAN:...`, `SECMAN-VISUAL-...`).
- The iOS app holds relay credentials only; it has no direct backend write path.
  Its generic section renderer permits additive reporting improvements without
  widening access or requiring every payload to be hard-coded in Swift.

## Verification

- Visual scanner: **458 passed**, including five new regression cases; 106
  existing SQLite datetime-adapter deprecation warnings. The login test now
  actually verifies the current secure cookie is replayed on upload.
- GitHub scanner: **662 passed, 4 skipped**. The skipped cases require its
  optional MySQL test database.
- iOS: source contract checked; the default Swift build engine failed with
  `Unknown error parsing property list`. Native build compiled the library but
  could not compile tests: `no such module 'Testing'`. This host selects Command
  Line Tools and has no Xcode application installed. No simulator/device test.
- `./gradlew build`: compilation and packaging reached the backend test task,
  then stopped producing progress. A thread dump of the test worker showed a
  MariaDB socket read inside Hibernate `SchemaDropperImpl` during test-context
  teardown. The run was cancelled (exit 130), not passed; the underlying DB
  wait/lock cause was not investigated further in this extension review.
- Runtime startup, `e2ejs`, and `e2evulnexception`: **not run**. Proton Pass
  resolution yielded no usable `SECMAN_HOST`; the configured database is
  `127.0.0.1:3306/secman`, not established as disposable. The exception skill
  explicitly wipes all database rows, so this requires a confirmed disposable
  configuration. No stack services were started or stopped.
- Root OWASP scanner reports zero changed application files because extensions
  are gitignored; that result does not cover the extension patch. The extension
  diff was reviewed semantically against A01-A10: A01/A04/A08 touched, no new
  access widening, unbounded pagination or data-integrity issue found. Existing
  page/request limits and transport credentials are preserved. This is not a
  full security audit of the extensions.

The visual extension fix is committed locally as `e88305f`; it was not pushed.
This report is an uncommitted document in the parent SecMan repository. Release
verification still needs the functioning Swift test toolchain, a completed
SecMan build, and a confirmed disposable stack for startup and both E2E gates.
