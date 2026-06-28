# Claude Code Agent Context

Security requirement, vulnerability and risk management platform.

## Stack

- Backend: Kotlin 2.3.21 / Java 25, Micronaut 4.10, Hibernate JPA → `src/backendng/`
- Frontend: Astro 6.3 + React 19 islands, Axios, JWT in localStorage → `src/frontend/`
- CLI: Kotlin + Picocli 4.7.7, AWS SDK v2 → `src/cli/`
- DB: MariaDB 11.4, Flyway + Hibernate auto-migration
- Build: Gradle 9.5.0 (Kotlin DSL)
- MCP: Streamable HTTP / JSON-RPC 2.0. `X-MCP-User-Email` header is **mandatory** on `tools/list` and `tools/call` (only `initialize` and `ping` exempt).

## Roles (RBAC)

`USER`, `ADMIN`, `VULN`, `RELEASE_MANAGER`, `REQ`, `REQADMIN`, `RISK`, `SECCHAMPION`, `REPORT`.

## Tooling Conventions (canonical, do not deviate)

- **Scripts**: `./scripts/` only.
- **Secrets**: `pass-cli` (Proton Pass) only. Never hardcode secrets.
- **Backend dev start**: `./scripts/startbackenddev.sh` (sources `pass-cli` env, runs Micronaut). Do not call `./gradlew run` directly.
- **Host URL in tests**: read `SECMAN_HOST` from `pass-cli`. Never hardcode `http://localhost:8080` or `http://localhost:4321`.

## Key Entities

- **Asset**: `id, name, type, ip, owner, description, lastSeen`; metadata `groups, cloudAccountId, cloudInstanceId, adDomain, osVersion`; relations `vulnerabilities, scanResults, workgroups, manualCreator, scanUploader`.
- **AwsAccountSharing**: `id, sourceUser, targetUser, createdBy, createdAt`. Directional, non-transitive. ADMIN-only.

## Unified Asset Access (any of)

1. ADMIN role
2. Asset in user's workgroup
3. `manualCreator == user`
4. `scanUploader == user`
5. `cloudAccountId` matches user's AWS UserMapping
6. `adDomain` matches user's domain UserMapping (case-insensitive)
7. `cloudAccountId` matches a sharing rule (`AwsAccountSharing`, directional)
8. `owner == username`
9. `cloudAccountId` matches an account assigned to a workgroup the user belongs to (`WorkgroupAwsAccount`, direct membership only)
10. `adDomain` matches a domain assigned to a workgroup the user belongs to (`WorkgroupAdDomain`, direct membership only)

Authoritative filter: `AssetFilterService.getAccessibleAssets()`. SQL pre-filters in materialized views are perf hints only — never the auth boundary. Same enforcement applies to MCP `get_overdue_assets`.

## API Endpoints (concise)

| Group | Endpoints | Roles |
|---|---|---|
| Auth | `POST /api/auth/login`, `GET /oauth/{authorize,callback}` | public |
| Import | `POST /api/import/{upload-xlsx, upload-nmap-xml, upload-vulnerability-xlsx, upload-user-mappings[-csv], upload-assets-xlsx}` | ADMIN |
| Assets | `GET/POST /api/assets`, `DELETE /api/assets/bulk` (ADMIN), `GET /api/assets/export` | mixed |
| Vulns | `GET /api/vulnerabilities/current`; export job: `POST /api/vulnerabilities/export` → `GET .../{jobId}/{status,download}`, `DELETE .../{jobId}`; history `GET .../export/history` (ADMIN/VULN/SECCHAMPION); `GET /api/vulnerability-exceptions`; `POST /api/vulnerability-exception-requests`; `GET .../pending/count`; SSE `GET /api/exception-badge-updates` | mixed |
| Outdated | `GET /api/outdated-assets[/{id}[/vulnerabilities]]`, `.../{last-refresh,count}`; `POST /api/materialized-view-refresh/trigger` (ADMIN); SSE `GET .../progress`; `GET .../status,history` | mixed |
| Workgroups | `GET/POST /api/workgroups` (ADMIN), `POST /api/workgroups/{id}/{users,assets}` (ADMIN), `GET/POST/DELETE /api/workgroups/{id}/aws-accounts`, `GET/POST/DELETE /api/workgroups/{id}/ad-domains` | ADMIN/member-scoped |
| Releases | `GET/POST/DELETE /api/releases[/{id}]` (ADMIN/REQADMIN to write), `GET /api/releases/compare`. Statuses: PREPARATION→ALIGNMENT→ACTIVE→ARCHIVED | mixed |
| CrowdStrike | `POST/GET /api/crowdstrike/{servers/import,vulnerabilities/save,vulnerabilities}` (ADMIN/VULN); `GET /api/crowdstrike/last-checkin` (PUBLIC, `text/plain` ISO-8601 or `"never"`) | mixed |
| User Mappings | `GET /api/user-mappings/{current,applied-history}`, `POST/PUT/DELETE /api/user-mappings[/{id}]` | ADMIN |
| AWS Sharing | `GET/POST /api/aws-account-sharing`, `DELETE .../{id}` | ADMIN |
| Heatmap | `GET /api/vulnerability-heatmap`; `POST .../refresh` (ADMIN); `GET /api/external/vulnerability-heatmap` (API-key, CORS) | mixed |
| Identity Providers | `GET/POST/PUT/DELETE /api/identity-providers[/{id}[/test]]` | ADMIN |
| Maintenance Banners | `GET /api/maintenance-banners/active` (PUBLIC); `GET/POST/PUT/DELETE /api/maintenance-banners[/{id}]` (ADMIN) | mixed |
| User Profile | `GET /api/users/profile`, `PUT .../change-password` (LOCAL only), `GET/PUT .../mfa-{status,toggle}` | auth |
| Notifications | `GET/PUT /api/notification-preferences`; `GET /api/notification-logs`; `.../export` (ADMIN) | mixed |
| CLI | `POST /api/vulnerabilities/cli-add` (ADMIN/VULN; auto-creates asset) | ADMIN/VULN |

MCP tool families mirror these (delegation required): `list_/create_/delete_release`, `set_release_status`, `compare_releases`; `list_workgroup_aws_accounts`, `add_/remove_workgroup_aws_account`, `list_workgroup_ad_domains`, `add_/remove_workgroup_ad_domain`; `list_/create_/delete_aws_account_sharing`; `get_vulnerability_heatmap`, `refresh_vulnerability_heatmap`; etc. See `docs/MCP.md`.

## Commands

```bash
# Backend
./gradlew build                            # build + tests
./scripts/startbackenddev.sh              # canonical dev start (pass-cli wraps gradle run)

# Frontend
cd src/frontend && npm run dev             # port 4321

# CLI
./gradlew :cli:shadowJar                   # build once
./scripts/secman <command>                # query servers, send-notifications, manage-user-mappings,
                                           # add-vulnerability, add-requirement, export-requirements, ...

# Tests
./gradlew :backendng:test --tests "*ServiceTest*"        # unit
./gradlew :backendng:test --tests "*IntegrationTest*"    # integration (Docker)
./gradlew :cli:test
./tests/e2e/run-e2e.sh                                   # Playwright with pass-cli secrets
```

CrowdStrike monitoring: `src/clinotify/check_crowdstrike_checkin.py` polls `/api/crowdstrike/last-checkin` and Telegrams when stale. Stdlib-only.

## Hard Principles

1. Security-first: file validation, input sanitization, RBAC. Security review before completion.
2. RBAC enforced at controller (`@Secured`) AND in UI.
3. Schema = Flyway migrations + Hibernate auto-update.
4. Always write tests. Source of truth for credentials and URLs is `pass-cli`.
5. **A change is complete only when** `./gradlew build` is clean **AND** `./scripts/startbackenddev.sh` starts cleanly. Compile-clean ≠ runtime-clean (Micronaut bean wiring, Flyway, SessionFactory only check at startup). Stop the backend after verifying.
5a. **Frontend changes are complete only when** `cd src/frontend && npm ci && npm run build` exits 0. TypeScript errors, missing imports, and broken Astro/React components are caught here — do not skip this step for any frontend file edit.
6. Tests route HTTP through `SECMAN_HOST` (from `pass-cli`). No hardcoded localhost URLs.
7. **Mandatory post-change E2E gates** (in addition to build + startup):
   - **`/e2ejs`** must report **0 JS errors** for both admin and normal-user runs against `SECMAN_HOST`. RBAC 403s on role-gated endpoints and documented 404s (e.g., `/api/wg-vulns`, `/api/domain-vulns` for users without mappings) are NOT JS errors. A page that throws or logs `console.error` IS — fix before merge.
   - **`/e2evulnexception`** must run the full vuln + exception lifecycle (MCP + UI, setup + teardown) with **0 failures**.

   Doc-only edits outside `src/`, `tests/`, `scripts/` may skip the gates — state so explicitly. Otherwise both gates are non-negotiable.

## Patterns (worth knowing)

### CSV/Excel Import
Validate (≤10MB, MIME, ext) → parse (POI / Commons CSV, UTF-8 BOM, ISO-8859-1 fallback) → header check (case-insensitive) → row parse (skip invalid, handle scientific notation) → dedupe (DB + file) → batch save → return `ImportResult{imported, skipped, errors[]}`.

### Asset merge
`findByName` → if exists, append groups, update IP, preserve owner; else create. Save.

### Auth
- Backend: `@Secured(SecurityRule.IS_AUTHENTICATED)` + `authentication.roles.contains("…")`.
- Frontend: JWT in `localStorage["authToken"]` → Axios `Authorization: Bearer …`.
- SSE: JWT in `?token=…` query param (EventSource has no header support).

### Transactional replace (CrowdStrike vuln import)
```kotlin
@Transactional
open fun importVulnerabilitiesForServer(batch: ...): ServerImportResult {
    val (asset, _) = findOrCreateAsset(batch)
    vulnerabilityRepository.deleteByAssetId(asset.id!!)
    vulnerabilityRepository.saveAll(vulnerabilities)
}
```
Idempotent; missing CVEs in next import = remediation.

**CRITICAL**: `Asset.vulnerabilities` MUST NOT use `cascade=ALL` or `orphanRemoval=true`. JPA cascade fights the manual delete-insert and silently drops 99% of rows (real incident: 166,812 → 1,819). Use explicit `vulnerabilityRepository.deleteByAssetId()` in the service. See `docs/CROWDSTRIKE_IMPORT.md`.

### Event-driven
```kotlin
@Serdeable data class UserCreatedEvent(val user: User, val source: String)
eventPublisher.publishEvent(UserCreatedEvent(saved, "MANUAL"))
@EventListener @Async open fun onUserCreated(e: UserCreatedEvent) { … }
```
Used for: user create → mapping apply, asset import → view refresh, vuln detect → email. <5ms delivery.

### OAuth state retry (race-tolerant)
Microsoft cached SSO callbacks can land in 100–500ms, before the state-save commit. `OAuthService.findStateByValueWithRetry` does exponential backoff (`oauthConfig.stateRetry` from `application.yml`). Tunable via `OAUTH_STATE_RETRY_*` env vars (see `docs/ENVIRONMENT.md`).

### Memory optimization (Feature 073)
`MemoryOptimizationConfig` reads `secman.memory.*`. Toggles: `MEMORY_LAZY_LOADING`, `MEMORY_BATCH_SIZE` (default 1000), `MEMORY_STREAMING_EXPORTS`. Monitor: `GET /memory`. Set any to `false` to roll back.

## Test Infrastructure

JUnit 6, Mockk, AssertJ, `@MicronautTest`. Integration tests run against an **external MariaDB** (no Docker/Testcontainers). Helpers in `src/backendng/src/test/kotlin/com/secman/testutil/`:
- `BaseIntegrationTest` — base for DB-backed tests; datasource comes from `application-test.yml`.
- `TestDataFactory` — admin/vuln/regular user, asset, vulnerability builders.
- `TestAuthHelper` — JWT login → bearer token.

Datasource env (set via `pass-cli`; defaults to a local `secman_test`): `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`. Schema is Hibernate `create-drop` (Flyway off in `test`), so **point `TEST_DB_*` only at a disposable test DB — never `DB_CONNECT`** (it would drop tables). Integration tests now run **unconditionally** — they fail (not skip) if no test DB is reachable.

```kotlin
class MyIntegrationTest : BaseIntegrationTest() { @Inject lateinit var repo: Repository }
```

One-time local setup (admin DB user):
```sql
CREATE DATABASE IF NOT EXISTS secman_test;
CREATE USER IF NOT EXISTS 'secman_test'@'localhost' IDENTIFIED BY 'secman_test';
GRANT ALL PRIVILEGES ON secman_test.* TO 'secman_test'@'localhost';
```

## File Layout

- Backend: `src/backendng/src/main/kotlin/com/secman/{domain,controller,service,repository,config,dto,filter,mcp}/`
- Frontend: `src/frontend/src/{pages,components,services,layouts}/`
- CLI: `src/cli/src/main/kotlin/com/secman/cli/{commands,service}/`
- Email templates: `src/backendng/src/main/resources/email-templates/`
- Backend config: `src/backendng/src/main/resources/application.yml`
- Env reference: `docs/ENVIRONMENT.md`

## E2E Runner

Triggered by `/e2eexception`, `/admin-asset-e2e`, `/e2ejs`, `/e2evulnexception` skills.

- Backend changes (Kotlin/Java) → restart required.
- Frontend changes → Vite hot-reload (no restart).
- Config (`astro.config.mjs`, `application.yml`) → restart.
- Logs: `.e2e-logs/` (gitignored). Max 5 fix iterations.
- **Liveness check is port-bind**, not HTTP: `lsof -iTCP:8080 -sTCP:LISTEN -n -P` (120s budget) and `:4321` (60s budget).
- **Functional checks** still go through `SECMAN_HOST` from `pass-cli`. Never `curl localhost`.

---

*Last updated: 2026-05-15*

## Recent Changes

- **OS + CVE vulnerability exceptions (apply to all assets)** — new exception **scope `OS`** added to the two-axis model (Feature 196). Combined with subject `CVE` it means "these CVEs are excepted on every asset whose `Asset.osVersion` contains the OS string (case-insensitive substring)", e.g. exception OS `Windows Server 2019` also matches `Windows Server 2019 Datacenter`. The OS string is stored in the existing `vulnerability_exception.scope_value` column — **no DB migration** (`scope` is already `VARCHAR(20)`). The match predicate lives in three synchronized places, all updated: the entity `VulnerabilityException.scopeMatches()`, the native-SQL `ExceptionMatchSql.EXCEPTION_MATCH` (`LOCATE(LOWER(scope_value), LOWER(a.os_version)) > 0`, fanning out to ~36 query sites), and the in-memory `ExceptionMatchIndex` in `VulnerabilityService` (which now projects `a.os_version` into `StatusFilteredVulnerabilityRow`). CrowdStrike import is honored automatically: `CrowdStrikeQueryService` computes `hasException` via the shared entity match and `Asset.osVersion` is already populated at import (query-time suppression model — no persistence-time filtering). Also wired through: `validateInvariants`/`createException`/affected-count/`previewExceptionImpact` (new repo methods `count|findByAssetOsVersionContainingIgnoreCase` and `…VulnerabilityIdInAndAssetOsVersionContainingIgnoreCase`), the `/api/vulnerability-exceptions/valid-combinations` matrix (CVE×OS, PRODUCT×OS, ALL_VULNS×OS [ADMIN/VULN]), MCP `create_exception_request` scope enum, and the frontend exception form/table/badges. Docs: `docs/MCP.md`.

- **Operating system per CrowdStrike asset** — the `query servers --save` import now captures each host's OS and stores it on `Asset.osVersion` (no separate command, no extra CrowdStrike round-trip). OS is extracted in `CrowdStrikeApiClientImpl.mapResponseToDtos` (shared) from the device entity (`os_version`, fallback `platform_name`, then `host_info.os_version`/`platform`) and carried on `CrowdStrikeVulnerabilityDto.osVersion` → `ServerVulnerabilityBatch.osVersion` (latest-detected non-blank per host) → `CrowdStrikeVulnerabilityBatchDto.osVersion`. Backend persistence (`createNewAsset`/`updateAsset`) was already in place. Now also exposed read-only on `GET /api/assets` (`AssetResponse.osVersion`, intentionally reversing part of HI-8) and shown as the **OS** column in the asset management UI. Docs: `docs/CROWDSTRIKE_IMPORT.md`.

- **`send-notification-users` recipient fan-out** — the global vulnerability-notification flow (also reused by `send-patch-notifications`) now notifies, for each affected AWS account, the union of: (1) the account owner(s) via `UserMapping.findByAwsAccountId`, (2) members of any workgroup containing an EC2 asset in the account (`AssetRepository.findDistinctWorkgroupMemberEmailsByCloudAccountId`, asset→workgroup→users), and (3) users granted access via the sharing feature (`AwsAccountSharingRepository.findTargetUserEmailsByAwsAccountId`, the inverse of the access-control query, honoring per-rule account selection). Recipients are deduplicated case-insensitively; an account with zero recipients across all three paths is reported as unmapped. Logic in `UserVulnerabilityNotificationService.resolveAccountRecipients`. Docs: `docs/CLI.md`.

- **CLI `notify-new-accounts`** — notifies users about new AWS account mappings created within a configurable look-back window (default: 24 h). Queries `UserMapping` rows where `aws_account_id IS NOT NULL AND created_at >= now() - hours` (new `UserMappingRepository.findRecentAwsAccountMappings`), groups by email, and sends one consolidated email per user. The notification body text is read from a file supplied via `--file`/`-f` (mandatory) — operators customise the message without redeploying. The account list is always appended below the custom text. New `NewAccountNotificationService` follows the same pattern as `UserVulnerabilityNotificationService`. Backend endpoint: `POST /api/cli/new-account-notifications/send` (ADMIN). Docs: `docs/CLI.md`.

- **CLI `send-patch-notifications` + MCP `send_patch_notifications`** — notify users about missing patches (overdue vulnerabilities), batched by the **first character of their email** (mandatory positional arg, e.g. `a`). `--days` (default 30) and `--dry-run` optional. Reuses `UserVulnerabilityNotificationService.sendUserVulnerabilityNotifications` (new `emailPrefix` recipient filter — case-insensitive `startsWith`, applied after AWS-account→user mapping) and the existing `POST /api/cli/user-vulnerability-notifications/send` endpoint (new optional `emailPrefix` field). MCP tool is ADMIN-only via delegation, permission `NOTIFICATIONS_SEND`. E2E coverage: Phase 8b in `scripts/test/test-e2e-vuln-exception-full.sh` (dry-run + missing-arg + non-admin negatives). Docs: `docs/CLI.md`, `docs/MCP.md`.

- **Feature 088** — AI-assisted risk-assessment answers (V215, V216). ADMIN and SECCHAMPION users (the assessment's assessor or requestor; ADMIN can act on any) can trigger an OpenRouter LLM to pre-fill compliance answers as draft `Response` rows with confidence + citations. Human always reviews and submits.
  - DB-backed master switch on `app_settings.ai_risk_assessment_enabled` (V216), flippable by ADMIN at `/appsettings`. Env var `AI_RISK_ASSESSMENT_ENABLED` only seeds the default at first create — once a row exists, the DB column is authoritative. `OPENROUTER_API_KEY` resolved via `pass-cli` is still required for the feature to actually call out. The UI "AI Pre-fill" button is gated on `GET /api/ai-risk-assessment/status` so it stays hidden when the switch is off.
  - Per-job hard cost cap `secman.ai.risk-assessment.max-cost-per-job-usd` (default 5 USD) plus global concurrency cap `max-concurrent-jobs-global` (default 2). Pre-flight rejects over-budget runs; mid-flight aborts and marks `FAILED`, retaining partial successes.
  - New endpoints under `/api/risk-assessments/{id}/ai-suggestions/...`: `POST /jobs`, `GET /jobs/{jobId}`, `DELETE /jobs/{jobId}` (cancel), `GET /jobs/{jobId}/events` (SSE), `GET` (list applied), `POST /clear-low-confidence`. All `@Secured("ADMIN","SECCHAMPION")` plus `AssessmentOwnershipGuard`.
  - Provenance: new `response.source` column (`MANUAL | AI_GENERATED | AI_EDITED`, indexed, backfilled `MANUAL`) plus `response.ai_suggestion_id` FK. `ResponseController` flips `AI_GENERATED → AI_EDITED` whenever a human changes answerType or comment.
  - Confidence band derivation: `score = 0.5·self_reported + 0.3·min(valid_citations, 3)/3 + 0.2·answer_is_not_UNKNOWN`. HIGH requires ≥1 valid citation (downgrade rule). Stored both raw + band so thresholds can retune without re-running the model.
  - Redaction (NFR-4): `PromptBuilder.redact()` scrubs emails, IPv4 addresses, and `*.internal/*.local/*.corp/*.lan/*.intranet` URLs from the prompt regardless of caller hygiene. Locked by `PromptBuilderTest`.
  - Re-run safety: `startJob` excludes `source = AI_EDITED` rows unless `force = true`. `clear-low-confidence` only touches `AI_GENERATED` rows whose linked suggestion has `confidenceBand = LOW`.
  - Mirrors `TranslationService` (JDK HttpClient + dedicated `ai` executor + Caffeine 24h cache) and `ExportJobService` (DB-backed job, IO executor, heartbeat, scheduled stale-job reclaim).
  - Docs: `docs/AI_RISK_ASSESSMENT.md`; spec/plan/tasks under `specs/088-ai-risk-assessment-answers/`.



- **CLI `asset-match-clear`** — reconciles AWS assets against an authoritative resource snapshot JSON downloaded from S3. Required env vars `AWS_ASSET_BUCKET_NAME` and `AWS_ASSET_BUCKET_KEY_NAME` (or `--bucket`/`--key`). Matches `Asset.cloudInstanceId` (case-insensitive) against the snapshot's `resourceId` set, scoped strictly to `accountId`s present in the snapshot — assets in other accounts are never touched (partial-snapshot safe). Default safety brake at 25% (`--max-delete-percent`, set 0 to disable); empty snapshots are rejected. Backend endpoint: `POST /api/assets/match-clear-aws` (ADMIN). Full reference: `docs/CLI_ASSET_MATCH_CLEAR.md`.

- **CrowdStrike stale-vuln cleanup hardening** (V213, V214). Closes the silent-remediation gap when a host's only matching vulnerability gets patched and it drops out of the next CLI batch:
  - `vulnerability.source` column (V213) replaces owner-based scoping in the reconcile sweep — CrowdStrike vulns on human-owned assets are now cleaned up. Canonical literals: `com.secman.constants.VulnerabilitySources` (`CROWDSTRIKE`, `XLSX`, `CLI_MANUAL`, `UNKNOWN`).
  - `crowdstrike_severity_history` table (V214) persists the union of all severities ever queried; the reconcile sweeps that union, not just today's `--severity` flag. Drift across runs no longer leaves stale rows.
  - CLI hard-fails (exit 2) on reconcile HTTP errors via `ReconcileFailedException` — operators/cron see the failure instead of a silent warning.
  - Repository method renamed: `VulnerabilityRepository.deleteStaleCrowdStrikeImports` → `deleteStaleVulnerabilitiesBySource`.
  - Coverage: `CrowdStrikeStaleVulnerabilityIntegrationTest` (5 cases).

- **Feature 087** — CrowdStrike legacy stale-asset cleanup. Adds rule B alongside the existing timestamp rule:
  - Canonical owner literal lives at `com.secman.constants.AssetOwners.CROWDSTRIKE_IMPORT`. Used as the writer in `CrowdStrikeVulnerabilityImportService.createNewAsset`, the dropdown source in `AssetController.getOwnerCandidates`, and the predicate parameter for `AssetRepository.findLegacyCrowdStrikeStale`.
  - New flag `secman.crowdstrike.cleanup.include-legacy` (env: `CROWDSTRIKE_CLEANUP_INCLUDE_LEGACY`, default `false`) gates rule B. Manual runs may override per-call via `includeLegacy: Boolean?` on the request body.
  - Rule-B fence: `owner = "CrowdStrike Import"` AND `crowdStrikeLastImportedAt IS NULL` AND no `manualCreator` AND no `scanUploader` AND `COALESCE(lastSeen, updatedAt, createdAt) < cutoff`.
  - `crowdstrike_cleanup_run` gains `legacy_candidate_count` and `legacy_deleted_count` (V210). Safety brake denominator widens to `countCrowdStrikeTracked + countLegacyCrowdStrikeTotal` when `include-legacy` is on.
  - Each cleanup candidate carries a `CleanupCandidateReason` enum (`TIMESTAMP_STALE` | `LEGACY_NULL_TIMESTAMP`) — surfaced in the dry-run summary and history table on the admin Falcon-config page.
