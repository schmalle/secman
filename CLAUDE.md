# Claude Code Agent Context

Security requirement, vulnerability and risk management platform.

## Stack

- Backend: Kotlin 2.4.10 / Java 25, Micronaut 5.0, Hibernate JPA → `src/backendng/`
- Frontend: Astro 7.1 + React 19 islands, Axios, JWT in the HttpOnly `secman_auth` cookie → `src/frontend/`
- CLI: Kotlin + Picocli 4.7.7, AWS SDK v2 → `src/cli/`
- DB: MariaDB 11.4, Flyway + Hibernate auto-migration
- Build: Gradle 9.6.1 (Kotlin DSL)
- MCP: Streamable HTTP / JSON-RPC 2.0. `X-MCP-User-Email` header is **mandatory** on `tools/list` and `tools/call` (only `initialize` and `ping` exempt).

## Roles (RBAC)

`USER`, `ADMIN`, `VULN`, `RELEASE_MANAGER`, `REQ`, `REQADMIN`, `RISK`, `SECCHAMPION`, `REPORT`.

## Tooling Conventions (canonical, do not deviate)

- **Scripts**: `./scripts/` only.
- **Skills**: `.claude/skills/` is the canonical, leading skill set for Claude Code sessions in this repo — authoritative over `.agents/skills/` (parallel copies maintained for Codex). On any divergence between the two trees, `.claude/skills/` wins. **Whenever a `.claude/skills/*/SKILL.md` file is edited, the matching `.agents/skills/*/SKILL.md` file must be updated in the same change** so the two never drift — translate Claude-specific mechanics to their Codex equivalent (e.g. Bash tool `dangerouslyDisableSandbox: true` ↔ `sandbox_permissions: "require_escalated"`) rather than copying verbatim. If a `.claude/skills/` entry has no `.agents/skills/` counterpart, flag it instead of silently creating one. When only asked to update "skills" without a harness specified, update `.claude/skills/` first, then port to `.agents/skills/`.
- **Secrets**: `pass-cli` (Proton Pass) only. Never hardcode secrets.
- **Backend dev start**: always `./scripts/startbackenddev.sh` (sources `pass-cli` env, runs Micronaut). Never call `./gradlew run` directly.
- **Frontend dev start**: always `./scripts/startfrontenddev.sh` (sources `pass-cli` env, runs `npm run dev`). Never call `npm run dev` directly.
- Both dev-start scripts require `pass-cli` and must be run **outside any sandbox** (e.g. Bash tool `dangerouslyDisableSandbox: true`, or escalated/unsandboxed permissions in other harnesses) — a sandboxed shell cannot reach `pass-cli` to source secrets, so the process fails to start cleanly.
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
| User Dashboard | `GET /api/user-dashboard` (aggregated personal todos, single round-trip) | auth |
| Notifications | `GET/PUT /api/notification-preferences`; `GET /api/notification-logs`; `.../export` (ADMIN) | mixed |
| CLI | `POST /api/vulnerabilities/cli-add` (ADMIN/VULN; auto-creates asset) | ADMIN/VULN |

MCP tool families mirror these (delegation required): `list_/create_/delete_release`, `set_release_status`, `compare_releases`; `list_workgroup_aws_accounts`, `add_/remove_workgroup_aws_account`, `list_workgroup_ad_domains`, `add_/remove_workgroup_ad_domain`; `list_/create_/delete_aws_account_sharing`; `get_vulnerability_heatmap`, `refresh_vulnerability_heatmap`; etc. See `docs/MCP.md`.

## Commands

```bash
# Backend
./gradlew build                            # build + tests
./scripts/startbackenddev.sh              # canonical dev start (pass-cli wraps gradle run); run outside any sandbox

# Frontend
./scripts/startfrontenddev.sh             # canonical dev start (pass-cli wraps npm run dev), port 4321; run outside any sandbox

# CLI
./gradlew :cli:shadowJar                   # build once
./scripts/secman <command>                # query servers, send-notifications, manage-user-mappings,
                                           # add-vulnerability, add-requirement, export-requirements, ...

# Tests
./gradlew :backendng:test --tests "*ServiceTest*"        # unit
./gradlew :backendng:test --tests "*IntegrationTest*"    # integration (external MariaDB, see Test Infrastructure)
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
5b. **Backend contract changes are complete only when the `extensions/` clients are updated in the same change.** Renaming a path, renaming or retyping a request/response field, tightening `@Secured`, or altering the auth scheme breaks those clients **silently** — nothing in this build compiles or tests against them, so the failure surfaces in production, not in CI. See **Extension Clients** for the surface and the verification rules. `/finalizer` automates the check.
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
- Frontend: the JWT lives in the **HttpOnly `secman_auth` cookie** (`AuthCookieService.AUTH_COOKIE_NAME`), not in JS-readable storage. Axios sends it via `withCredentials: true` (set globally in `utils/csrf.ts`); fetch calls use the `authenticated*` helpers in `utils/auth.ts` / `services/`. `sessionStorage["user"]` holds only the display/role payload — never a token.
- External/CLI clients: `POST /api/auth/login` returns the JWT **only** in `Set-Cookie: secman_auth=…`; they re-send it as `Authorization: Bearer …` (the bearer reader stays active alongside cookie auth).
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

## Extension Clients (`extensions/`)

Independent Python repos with their own GitHub remotes, **gitignored by this repo** (`.gitignore`) — root `git status` never shows them, and no build or test here covers them. That invisibility is exactly why principle 5b exists.

- `secman_ai_github` — GitHub security scanner
- `secman_visual_check` — external attack-surface scanner

Contract surface **as of 2026-08-01** (orientation only — rediscover with `grep -rnE '/api/|"/mcp"|X-MCP-User-Email' extensions --include='*.py' --exclude-dir=.venv` rather than trusting this list, or a newly added call gets checked by nobody):
`POST /api/auth/login`, `POST /api/vulnerabilities/cli-add`, `GET /api/vulnerabilities/current`, `PUT /api/assets/import`, and MCP `/mcp` (`X-MCP-API-Key` + `X-MCP-User-Email`; tools `get_vulnerabilities`, `add_vulnerability`, `create_asset`).

When you change any of those endpoints, verify all five dimensions against the client: **path, HTTP method, request field names, response fields the client reads, and `@Secured` roles / required headers**. Field names matter most — Jackson drops unknown keys without error, so a rename makes the client "succeed" while sending nothing. Update the client's `tests/` too; a test asserting the old shape is drift.

Fix and commit **inside the extension repo, path-scoped** (`git -C extensions/<repo> commit -m "…" -- <files>`) so pre-existing dirty files are not swallowed. Never `git add -A` / `commit -a`, and **never push** — those repos have their own review. An `@Secured` tightening is a *report*, not a fix: you cannot see which roles the client's service account holds.

## E2E Runner

Triggered by `/e2eexception`, `/admin-asset-e2e`, `/e2ejs`, `/e2evulnexception`, `/importtest`, `/crowdstrike-vuln-match` skills.

- **Cold start is mandatory**: every skill that touches the running stack assumes backend and frontend must be started by the skill itself. It always begins by killing any running backend/frontend via `./scripts/stopbackenddev.sh` / `./scripts/stopfrontenddev.sh` (unconditional — even if ports look free; the scripts are safe no-ops), then starts both fresh via the canonical start scripts. Never reuse an already-running instance; never assume services are up.
- Backend changes (Kotlin/Java) → restart required.
- Frontend changes → Vite hot-reload (no restart).
- Config (`astro.config.mjs`, `application.yml`) → restart.
- Logs: `.e2e-logs/` (gitignored). Max 5 fix iterations.
- **Liveness check is port-bind**, not HTTP: `lsof -iTCP:8080 -sTCP:LISTEN -n -P` (120s budget) and `:4321` (60s budget).
- **Functional checks** still go through `SECMAN_HOST` from `pass-cli`. Never `curl localhost`.

---

*Last updated: 2026-08-01*

## Recent Changes

Three newest only; everything older is archived verbatim in `docs/CHANGELOG.md`.

- **Risk assessment on newly discovered AWS accounts, anchored to the current requirements version** — an AWS account-mapping import that introduces an account ID present on no existing `user_mapping` row can now start a risk assessment for that account's owner, measured against **the current version of the security requirements**: the single `ACTIVE` release. Each auto-started assessment is *pinned* to it (`lockedRelease` / `isReleaseLocked` / `contentSnapshotTaken` — fields that existed but were never set by anything), and `ResponseController.getRequirementsForAssessment` resolves its questionnaire from that release's `requirement_snapshot` rows scoped by the use case tag, so importing more requirements while the assessment is open cannot change the questions already asked. New `ReleaseRequirementScopeService` owns "which requirements does this release contribute for this use case" (and fixes the raw-substring JSON match in `RequirementController.getRequirementsByUseCase`, where use case `1` matched a requirement tagged `[11,12]`); the dead JPQL branch in `getRequirementsForAssessment` — which referenced non-existent `r.useCases`/`u.standards`, threw, and silently fell through to *all* requirements — is gone. Validation is fail-fast before anything imports: no ACTIVE release, or an ACTIVE release with no requirements for the use case, is a 400 (CLI exit 2). `UserMappingBulkImportService` now holds the validate → import → notify → start-assessments sequence once, shared by REST `POST /api/user-mappings/bulk` and MCP. MCP `import_user_mappings` was rewired onto it (it previously wrote rows one at a time and never detected new accounts) and gains `startRiskAssessment` / `riskAssessmentUseCase` / `riskAssessmentDeadlineDays`; new read tool `list_aws_account_risk_assessments` (ADMIN, `ASSESSMENTS_READ`). **Both** MCP permission maps are updated — `import_user_mappings` was missing from `McpToolPermissionService.checkPermissionSetForTool`, whose `else -> false` made it unreachable over the streamable HTTP transport. No schema change. E2E: `/aws-account-risk-assessment` skill + `scripts/test/test-e2e-aws-account-risk-assessment.sh`. Docs: `docs/AWS_ACCOUNT_RISK_ASSESSMENT.md`, `docs/CLI.md`, `docs/MCP.md`.
- **Longest-open findings by AWS account (ADMIN only)** — new admin-only report ranking AWS accounts by the age of their oldest still-open, non-excepted vulnerability, anchored on `COALESCE(first_seen_at, scan_timestamp)`. Surfaced three ways: `GET /api/admin/account-finding-age/top?limit=10` (backing a new `/account-finding-age` page plus an ADMIN-only card on the home dashboard), MCP `get_top_accounts_by_finding_age` (ADMIN via delegation, `VULNERABILITIES_READ`), and CLI `send-account-finding-age-report` which emails the table to all ADMIN-role users (deliberately excluding REPORT users). Every row carries an account name: the new `aws_account` table (V245) holds admin-supplied names, edited inline on the report page via `PUT /api/admin/aws-accounts/{id}/name`, and unnamed accounts fall back to their bare 12-digit ID. Docs: `docs/CLI.md`, `docs/MCP.md`.
- **Vulnerability exception expiry reminders** — CLI `send-exception-expiry-reminders` / MCP `send_exception_expiry_reminders` (ADMIN, `NOTIFICATIONS_SEND`) notify exception owners when their `VulnerabilityException` is expiring exactly `--days`/`days` days from today (default **7**). Owner = the exception's `createdBy` username resolved to `User.email`; one consolidated email per owner listing all of their expiring exceptions. Sent-state table `vulnerability_exception_expiry_reminder` (V244, `VulnerabilityExceptionExpiryReminder`, unique on `(exception_id, expiration_date)`) makes the reminder idempotent per (exception, expiration date) pair — safe to run daily via cron without re-notifying, while editing an exception to a new expiration date triggers a fresh reminder once it re-enters the window. Backend: `VulnerabilityExceptionExpiryReminderService`, endpoint `POST /api/cli/vulnerability-exception-expiry-notifications/send` (ADMIN). No scheduler — CLI/MCP-triggered only, matching `send-patch-notifications`/`notify-new-accounts`; both `./scripts/secman` (Proton Pass) and `./scripts/secmancliaws.sh` (AWS Secrets Manager) dispatch the new subcommand via the existing generic CLI launchers — no new wrapper scripts needed. Docs: `docs/CLI.md`, `docs/MCP.md`.
