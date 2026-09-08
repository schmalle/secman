# Central extension results

The user approved implementing all five recommendations in the preceding review.
This design implements them additively, preserving production data and legacy
`cli-add`. No production deployment or destructive E2E execution is authorized.

## Architecture

An admin registers a scanner (source, service user, freshness threshold) and binds
it to existing inventory subjects. A subject always has an Asset for existing
access, ownership, exception and SLA workflows; GitHub subjects also reference
the existing GithubRepository. Registering a GitHub subject links an existing
legacy `owner/repo` asset when unambiguous or creates a REPOSITORY asset with the
repository owner's mapped SecMan user. Repository identity is instance plus
numeric GitHub ID, never mutable name alone. No global merge or deletion of
legacy findings is performed.

Each submission is one bounded, atomic terminal scan snapshot for exactly one
registered subject. The scanner ID and bound service user constrain writes even
for MCP keys. A runKey identifies a retry; same key and content returns the same
acknowledgement, different content conflicts. Stale/out-of-order submissions
cannot regress a newer subject snapshot. Up to 500 findings and 5 MiB decoded
evidence per submission. Oversized or invalid snapshots fail atomically and
never resolve prior findings. SUCCESS plus completeCoverage=true is the only
combination allowed to resolve absent findings, within that subject and scanner.
PARTIAL, FAILED, SKIPPED and incomplete SUCCESS preserve missing findings.

IntegrationFinding retains rich evidence and OPEN/RESOLVED history separately
from active Vulnerability projections. Active projections reuse existing
exceptions and SLA calculations; resolving removes only this integration's
projection (existing exception requests use ON DELETE SET NULL). Reopening uses
the stable namespaced identifier and original firstSeenAt. Historic integration
evidence remains available. All reads/attachments use AssetFilterService.
Legacy synthetic IDs may be explicitly adopted only on the bound asset, only
from CLI_MANUAL, and only when not already claimed by another integration.
Scanner observations stay separate from Dependabot counts; summaries label both.

## Version 1 wire contract

Base: `/api/integrations/v1`. Existing cookie/bearer auth; admin config, assigned
service-user ingestion, authenticated asset-scoped reads. MCP uses the same
service with delegated identity; a new INTEGRATIONS_WRITE permission gates writes.
Legacy MCP permissions remain unchanged for compatibility.

`POST /scanners` (ADMIN), `GET /scanners` (scoped):
```json
{"name":"Visual production","source":"VISUAL","serviceUserId":12,"staleAfterHours":24,"enabled":true}
```
Source is GITHUB_AI or VISUAL. Response includes id and those fields.
`PUT /scanners/{id}` updates config. `POST /scanners/{id}/subjects` (ADMIN):
```json
{"assetId":42,"githubRepositoryId":null}
```
Exactly one input ID; GitHub input creates/resolves its linked Asset. Optional
owner/workgroup changes continue through existing inventory management.
`GET /scanners/{id}/subjects?page=0&size=100` returns a standard page envelope:
`{content,totalElements,totalPages,number,size}`. Subject rows contain id,
scannerId,assetId,githubRepositoryId,name,uri,owner,githubInstance,githubRepoId,
lastStatus,lastScanAt,lastSuccessfulScanAt,stale,openFindings. Nullable fields
are explicit. Disabled scanners cannot submit.

`POST /runs` accepts:
```json
{"scannerId":1,"subjectId":2,"runKey":"scan-uuid-subject-id","status":"SUCCESS","completeCoverage":true,"startedAt":"2026-09-06T10:00:00Z","completedAt":"2026-09-06T10:01:00Z","metadataJson":"{}","findings":[{"externalId":"stable-rule-location-id","legacyIds":[],"severity":"HIGH","title":"Finding title","description":"Details","recommendation":"Fix guidance","evidence":"Observed evidence","filePath":null,"lineRange":null,"url":null,"confidence":0.9,"engine":null,"model":null,"commitSha":null,"issueUrl":null,"fixPrUrl":null,"attachments":[{"fileName":"evidence.txt","contentType":"text/plain","base64":"ZXZpZGVuY2U="}]}]}
```
Finding severities: CRITICAL,HIGH,MEDIUM,LOW,INFO. Stable identity excludes title
and severity. Text/URL/ID sizes are validated; attachment types PNG/JPEG/plain
text/JSON only, content validated before persistence, max 1 MiB each, 5 MiB total.
No server-side fetching of client URLs. Timestamps are UTC and bounded; full
run contents are validated before any mutation.
Response: `{id,scannerId,subjectId,status,accepted,resolved,replayed}`.
MCP tools: `submit_integration_run` with this body;
`list_integration_subjects` arguments scannerId/page/size and same page result.

Read APIs:
- GET `/summary`: scanners,totalSubjects,healthySubjects,failedSubjects,
  unscannedSubjects,staleSubjects,openFindings.
- GET `/runs`: page/size,scannerId,subjectId; page of id,scannerId,subjectId,
  scannerName,subjectName,status,completeCoverage,startedAt,completedAt,accepted,
  resolved,metadataJson.
- GET `/findings`: page/size,scannerId,subjectId,githubRepositoryId,source,owner,
  severity,state,search. Page rows include id,scannerId,scannerName,source,
  subjectId,subjectName,assetId,githubRepositoryId,owner,externalId,severity,state,
  all finding text fields,firstSeenAt,lastSeenAt,resolvedAt,vulnerabilityId
  (active projection DB id),excepted, and attachments `{id,fileName,contentType}`.
- GET `/findings/{id}` returns the same detailed row, scoped.
- GET `/findings/{id}/attachments/{attachmentId}` returns safe attachment bytes,
  nosniff and download disposition, after scoped authorization.

The integrations page has source/owner/severity/state filters, coverage cards,
scanner configuration (ADMIN), subject/run history, full finding detail and
attachment downloads. It links active projections to the existing asset and
exception workflow. GitHub repository detail shows a separate AI findings panel
and counts, never relabeling AI observations as Dependabot alerts.

A new role-gated `integrations` relay section exposes aggregate freshness and
coverage; no raw evidence or user identity goes into the relay. Existing iOS
generic rendering supports it without changing envelope version 2. A generic
INTEGRATION_SCAN_FAILED/INTEGRATION_STALE chat event uses existing subscriptions
and non-sensitive scanner health only, with a central integrations link.

Both extension clients opt into version 1 with SECMAN_SCANNER_ID / CLI option;
legacy mode remains compatible. They discover permitted subjects, upload rich
results including zero findings and failed/skipped outcomes, preserve dry-run
and credentials, and do not auto-fallback to legacy after ingestion rejection.
GitHub's High/Critical-only review is explicitly incomplete coverage and cannot
auto-resolve findings. Visual scope is per registered asset: a page-only subset
must not declare full asset coverage; default completeCoverage=false unless its
configured full target set is demonstrably covered. This favors retaining an
open finding over falsely claiming remediation.

## Verification and release

Tests cover idempotent replay/conflict, concurrent writes, cross-scanner and
cross-subject denial, unauthorized reads/attachments, partial/failed/older scan
safety, zero findings, severity updates, firstSeen preservation, INFO, retained
evidence, adoption conflicts, and frontend filtering. Contract fixtures are
shared in docs/contracts and used by backend and extension tests. CI checks
their shape and behavior with pinned extension revisions where available.
Run focused tests, extension suites, Gradle build, frontend npm ci/build/lint,
OWASP review, startup and required E2E gates on a confirmed disposable stack.
The previous turn's missing disposable-stack confirmation still blocks the
destructive E2E driver, not local implementation or non-destructive tests.
