# Claude Code Agent Context

## Project Overview

**secman** - Security requirement and risk assessment management tool

**Stack**: Kotlin 2.3.20 / Java 21, Micronaut 4.10, Hibernate JPA | Astro 6.1, React 19, Bootstrap 5.3 | MariaDB 11.4, Gradle 9.4.1

**Architecture**:

- Backend: `src/backendng/` - Domain (JPA) → Repository → Service → Controller (REST)
- Frontend: `src/frontend/` - Astro + React islands, Axios, localStorage JWT
- CLI: `src/cli/` - CrowdStrike API queries, notification emails
- Security: JWT auth, OAuth2/OIDC, RBAC (USER, ADMIN, VULN, RELEASE_MANAGER, REQ, REQADMIN, RISK, SECCHAMPION, REPORT)
- MCP: `X-MCP-User-Email` header is **mandatory** for `tools/list` and `tools/call` endpoints (only `initialize` and `ping` are exempt)

## Key Entities

### Asset

- **Core**: id, name, type, ip, owner, description, lastSeen
- **Metadata**: groups, cloudAccountId, cloudInstanceId, adDomain, osVersion
- **Relations**: vulnerabilities, scanResults, workgroups, manualCreator, scanUploader

### AWS Account Sharing

- **Core**: id, sourceUser, targetUser, createdBy, createdAt
- **Semantics**: Directional, non-transitive sharing of AWS account visibility between users
- **Access**: ADMIN-only management via REST API, MCP tools, and admin UI

## Unified Access Control

Users access assets if **ANY** is true:

1. User has ADMIN role (universal)
2. Asset in user's workgroup
3. Asset manually created by user
4. Asset discovered via user's scan upload
5. Asset's cloudAccountId matches user's AWS mappings (UserMapping)
6. Asset's adDomain matches user's domain mappings (case-insensitive, UserMapping)
7. Asset's cloudAccountId matches shared AWS accounts via AwsAccountSharing (directional, non-transitive)
8. Asset's owner matches user's username
9. Asset's `cloudAccountId` matches an AWS account assigned to a workgroup the user belongs to (via WorkgroupAwsAccount, direct membership only — no hierarchy propagation)

## API Endpoints

**Import**: POST /api/import/{upload-xlsx, upload-nmap-xml, upload-vulnerability-xlsx, upload-user-mappings, upload-user-mappings-csv, upload-assets-xlsx}

**Assets**: GET/POST /api/assets, DELETE /api/assets/bulk (ADMIN), GET /api/assets/export

**Vulnerabilities**: GET /api/vulnerabilities/current, POST /api/vulnerabilities/export (start job), GET /api/vulnerabilities/export/{jobId}/status, GET /api/vulnerabilities/export/{jobId}/download, DELETE /api/vulnerabilities/export/{jobId} (cancel), GET /api/vulnerabilities/export/history (ADMIN/VULN/SECCHAMPION), GET /api/vulnerability-exceptions, POST /api/vulnerability-exception-requests, GET /api/vulnerability-exception-requests/pending/count, GET /api/exception-badge-updates (SSE)

**Outdated Assets**: GET /api/outdated-assets[/{id}[/vulnerabilities]], GET /api/outdated-assets/{last-refresh,count}, POST /api/materialized-view-refresh/trigger (ADMIN), GET /api/materialized-view-refresh/{progress (SSE), status, history}

**Notifications**: GET/PUT /api/notification-preferences, GET /api/notification-logs, GET /api/notification-logs/export (ADMIN)

**Workgroups**: POST/GET /api/workgroups (ADMIN), POST /api/workgroups/{id}/{users,assets} (ADMIN)

- POST/GET/DELETE /api/workgroups/{id}/aws-accounts (ADMIN)
- MCP tools: `list_workgroup_aws_accounts`, `add_workgroup_aws_account`, `remove_workgroup_aws_account` (ADMIN + User Delegation)

**Releases**: POST /api/releases (ADMIN/REQADMIN), GET /api/releases, GET /api/releases/compare, DELETE /api/releases/{id} (ADMIN/REQADMIN)

- Statuses: PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED
- MCP tools: `list_releases`, `get_release`, `create_release`, `delete_release`, `set_release_status`, `compare_releases` (create/delete require ADMIN/REQADMIN; status management requires ADMIN/RELEASE_MANAGER; all require User Delegation)

**Auth**: POST /api/auth/login, GET /oauth/{authorize,callback}

**CrowdStrike**: POST /api/crowdstrike/servers/import (ADMIN/VULN), GET /api/crowdstrike/servers/import/latest (ADMIN/VULN), POST /api/crowdstrike/vulnerabilities/save (ADMIN/VULN), GET /api/crowdstrike/vulnerabilities (ADMIN/VULN), GET /api/crowdstrike/last-checkin (PUBLIC, returns ISO-8601 timestamp of the last import as `text/plain`, or `"never"` if no import has happened)

- Monitoring script: `src/clinotify/check_crowdstrike_checkin.py` polls the public checkin endpoint and sends a Telegram alert when the last import is older than `--max-age-minutes` (or `never`). Stdlib-only Python; see `src/clinotify/README.md`.

**User Mappings**: GET /api/user-mappings/{current,applied-history} (ADMIN), POST/PUT/DELETE /api/user-mappings[/{id}] (ADMIN)

**AWS Account Sharing**: GET/POST /api/aws-account-sharing (ADMIN), DELETE /api/aws-account-sharing/{id} (ADMIN)

- MCP tools: `list_aws_account_sharing`, `create_aws_account_sharing`, `delete_aws_account_sharing` (all require ADMIN + User Delegation)

**Vulnerability Heatmap**: GET /api/vulnerability-heatmap (authenticated), POST /api/vulnerability-heatmap/refresh (ADMIN), GET /api/external/vulnerability-heatmap (API key auth, CORS-enabled for external consumers)

- MCP tools: `get_vulnerability_heatmap` (VULNERABILITIES_READ + User Delegation), `refresh_vulnerability_heatmap` (ADMIN + User Delegation)

**Identity Providers**: GET /api/identity-providers[/{enabled,{id}}], POST/PUT/DELETE /api/identity-providers[/{id}], POST /api/identity-providers/{id}/test

**Maintenance Banners**: GET /api/maintenance-banners/active (PUBLIC), GET /api/maintenance-banners (ADMIN), GET/POST /api/maintenance-banners[/{id}] (ADMIN), PUT/DELETE /api/maintenance-banners/{id} (ADMIN)

**User Profile**: GET /api/users/profile, PUT /api/users/profile/change-password (LOCAL users only), GET /api/users/profile/mfa-status, PUT /api/users/profile/mfa-toggle

**CLI Add Vulnerability**: POST /api/vulnerabilities/cli-add (ADMIN/VULN) - Add or update vulnerability with auto-asset creation

## Development

**Git**: Commits `type(scope): description`, Branches `###-feature-name`

**Tooling Conventions** (canonical — do not deviate):

- **Script directory**: `./scriptpp/` is the ONLY supported script directory. Never reference, create, or assume `./scripts/` at the repo root — that path is deprecated and any remaining usage is a bug to fix.
- **Credential ingestion**: Proton Pass `pass-cli` is the required tool for command-line secret retrieval (replaces previously used `op run` / 1Password). All scripts that need credentials must source them via `pass-cli`; never hardcode secrets and never re-introduce `op run`.
- **Backend dev start**: `./scriptpp/startbackenddev.sh` is the canonical local-dev entrypoint for the Micronaut backend (it wires `pass-cli`-sourced env vars into `./gradlew run`). Use it instead of running `./gradlew run` directly.

**Commands**:

- Backend (build): `./gradlew build`
- Backend (run dev): `./scriptpp/startbackenddev.sh` — canonical local-dev entrypoint; sources secrets via `pass-cli` and starts Micronaut in run mode
- Frontend: `npm run dev`
- CLI: Build JAR once with `./gradlew :cli:shadowJar`, then use `./scriptpp/secman <command>`
  - `./scriptpp/secman help` - Show all commands and options
  - `./scriptpp/secman query servers --dry-run` - Query CrowdStrike
  - `./scriptpp/secman send-notifications --dry-run` - Email notifications
  - `./scriptpp/secman manage-user-mappings --help` - User mappings. The `list` subcommand supports `--send-email` (distribute statistics to ADMIN/REPORT users), `--type AWS|DOMAIN|ALL` (scope to AWS or domain mappings), and `--output <file>` (download mappings from the secman DB to a file; CSV is round-trip compatible with `import`). The `download-s3` subcommand downloads an AWS account mapping file directly from an S3 bucket to a local path with no backend involvement. The `print-s3` subcommand downloads a mapping file from S3, parses it, and prints the identified AWS account mappings to the console (no disk write, no backend; defaults to `--type AWS`). All three S3 subcommands only require AWS `s3:GetObject` permissions.
  - `./scriptpp/secman export-requirements --format xlsx` - Export requirements
  - `./scriptpp/secman add-requirement --shortreq "text"` - Add requirement
  - `./scriptpp/secman add-vulnerability --hostname host --cve CVE-xxx --criticality HIGH` - Add vulnerability

**Test Commands**:

- All tests: `./gradlew build` (includes unit + integration tests)
- Unit tests only: `./gradlew :backendng:test --tests "*ServiceTest*"`
- CLI tests: `./gradlew :cli:test`
- Integration tests (requires Docker): `./gradlew :backendng:test --tests "*IntegrationTest*"`
- Specific test class: `./gradlew :backendng:test --tests "VulnerabilityServiceTest"`
- E2E tests (Playwright): Setup: `cd tests/e2e && npm install && npx playwright install chrome msedge`
- E2E tests (run with pass-cli secrets): `./tests/e2e/run-e2e.sh`
- E2E tests (run manually): `cd tests/e2e && SECMAN_BASE_URL=http://localhost:4321 SECMAN_ADMIN_NAME=... SECMAN_ADMIN_PASS=... SECMAN_USER_USER=... SECMAN_USER_PASS=... npx playwright test`

**Principles**:

1. Security-First: File validation, input sanitization, RBAC, security review required
2. API-First: RESTful, backward compatible
3. RBAC: @Secured on endpoints, role checks in UI
4. Schema Evolution: Flyway migrations + Hibernate auto-update
5. Never write testcases
6. A feature is only complete when `./gradlew build` shows no errors

## Common Patterns

### CSV/Excel Import

1. Validate file (≤10MB, extension, content-type)
2. Parse (Apache POI/Commons CSV), detect delimiter/encoding (UTF-8 BOM, ISO-8859-1 fallback)
3. Validate headers (case-insensitive), parse rows (skip invalid, handle scientific notation)
4. Check duplicates (DB + file), batch save
5. Return ImportResult: {imported, skipped, errors[]}

### Entity Merge (Asset)

1. Find by name → 2. Merge if exists (append groups, update IP, preserve owner) OR create if new → 3. Save

### Authentication

- Backend: `@Secured(SecurityRule.IS_AUTHENTICATED)`, `authentication.roles.contains("ADMIN"/"VULN")`
- Frontend: JWT in localStorage (`authToken`) → Axios headers (`Authorization: Bearer <token>`)
- SSE: JWT passed as query parameter (`?token=<jwt>`) since EventSource doesn't support custom headers

### Duplicate Prevention Pattern

**Pattern**: Transactional replace for CrowdStrike vulnerability imports

```kotlin
@Transactional
open fun importVulnerabilitiesForServer(batch: CrowdStrikeVulnerabilityBatchDto): ServerImportResult {
    val (asset, isNewAsset) = findOrCreateAsset(batch)
    // DELETE existing vulnerabilities
    vulnerabilityRepository.deleteByAssetId(asset.id!!)
    // INSERT new vulnerabilities
    vulnerabilityRepository.saveAll(vulnerabilities)
}
```

**Guarantees**: Idempotency, no duplicates, atomicity, remediation tracking
**Documentation**: docs/CROWDSTRIKE_IMPORT.md
**CRITICAL**: Asset.vulnerabilities MUST NOT use `cascade = [CascadeType.ALL]` or `orphanRemoval = true`. JPA cascade conflicts with manual delete-insert pattern, causing 99% data loss (e.g., 166,812 imported → 1,819 retained). Use explicit `vulnerabilityRepository.deleteByAssetId()` in service layer instead.

### Event-Driven Architecture

**Pattern**: @EventListener @Async for cross-service communication

```kotlin
@Serdeable data class UserCreatedEvent(val user: User, val source: String)
eventPublisher.publishEvent(UserCreatedEvent(savedUser, "MANUAL"))
@EventListener @Async open fun onUserCreated(event: UserCreatedEvent) { applyFutureUserMapping(event.user) }
```

**Use**: User creation → mapping application, Asset import → view refresh, Vuln detection → email
**Performance**: <5ms event delivery, non-blocking

### Test Infrastructure

**Stack**: JUnit 6, Mockk, Testcontainers (MariaDB), AssertJ
**Structure**:

- Unit tests: `src/backendng/src/test/kotlin/com/secman/service/` - Mockk for mocking dependencies
- Integration tests: `src/backendng/src/test/kotlin/com/secman/integration/` - Testcontainers for real DB
- CLI tests: `src/cli/src/test/kotlin/com/secman/cli/commands/` - Picocli parameter validation
- Test utilities: `src/backendng/src/test/kotlin/com/secman/testutil/`
  - `BaseIntegrationTest.kt` - Testcontainers setup, skips when Docker unavailable
  - `TestDataFactory.kt` - Create test users, assets, vulnerabilities
  - `TestAuthHelper.kt` - Get JWT tokens for authenticated requests

**Patterns**:

```kotlin
// Unit test with Mockk
@MicronautTest
class ServiceTest {
    @MockBean(Repository::class) val repo = mockk<Repository>()
    every { repo.save(any()) } returns savedEntity
}

// Integration test extending BaseIntegrationTest
@EnabledIf("com.secman.testutil.DockerAvailable#isDockerAvailable")
class MyIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var repo: Repository
}
```

### OAuth Robustness Pattern

**Problem**: Microsoft Azure OAuth callbacks can arrive in 100-500ms with cached SSO, before state-save transaction commits.
**Solution**: Exponential backoff retry + configurable parameters

```kotlin
// State lookup with retry (OAuthService.kt)
fun findStateByValueWithRetry(stateToken: String): Optional<OAuthState> {
    val config = oauthConfig.stateRetry  // from application.yml
    var currentDelayMs = config.initialDelayMs
    repeat(config.maxAttempts) { attempt ->
        val result = oauthStateRepository.findByStateToken(stateToken)
        if (result.isPresent) return result
        Thread.sleep(currentDelayMs)
        currentDelayMs = minOf((currentDelayMs * config.backoffMultiplier).toLong(), config.maxDelayMs)
    }
    return Optional.empty()
}
```

**Config**: `OAuthConfig.kt` reads from `secman.oauth.*` in application.yml
**Environment Variables**:

- `OAUTH_STATE_RETRY_MAX_ATTEMPTS` (default: 5) - Max retry attempts for state lookup
- `OAUTH_STATE_RETRY_INITIAL_DELAY` (default: 100ms) - Initial retry delay
- `OAUTH_STATE_RETRY_MAX_DELAY` (default: 500ms) - Max retry delay
- `OAUTH_STATE_RETRY_BACKOFF_MULTIPLIER` (default: 1.5) - Exponential backoff multiplier
- `OAUTH_TOKEN_EXCHANGE_MAX_RETRIES` (default: 2) - Token exchange retry count
- `OAUTH_TOKEN_EXCHANGE_RETRY_DELAY` (default: 500ms) - Token exchange retry delay

### Memory Optimization Pattern

**Feature 073**: SQL-level filtering, batched processing, streaming exports
**Config**: `MemoryOptimizationConfig.kt` reads from `secman.memory.*` in application.yml
**Environment Variables**:

- `MEMORY_LAZY_LOADING` (default: true) - Enable LAZY loading for entity relationships
- `MEMORY_BATCH_SIZE` (default: 1000) - Batch size for duplicate cleanup and streaming operations
- `MEMORY_STREAMING_EXPORTS` (default: true) - Enable streaming exports to reduce memory footprint

**Monitoring**: GET /memory endpoint returns JVM heap metrics (used, max, free, total in MB)
**Rollback**: Set environment variables to `false` to revert to original behavior

## File Locations

- Backend: `src/backendng/src/main/kotlin/com/secman/{domain,controller,service,repository,config}/`
- Frontend: `src/frontend/src/{components,pages,services}/`
- CLI: `src/cli/src/main/kotlin/com/secman/cli/{commands,service}/`
- Email Templates: `src/backendng/src/main/resources/email-templates/`
- Config: `src/backendng/src/main/resources/application.yml`
- Environment Docs: `docs/ENVIRONMENT.md`

## E2E Test Runner Integration

### Quick Start

Run `/e2eexception`, `/admin-asset-e2e`, or `/e2ejs` to start the full E2E test loop. This will:

1. Start the Micronaut backend and Astro frontend
2. Run the E2E test script
3. Automatically fix failures and retry

### Architecture

- **E2E Tests**: Shell script at `./scriptpp/e2e-test.sh`

### E2E Runner Rules

- Backend changes (Kotlin/Java) always require a backend restart
- Frontend changes usually hot-reload via Vite — no restart needed
- Config changes (`astro.config.mjs`, `application.yml`) require restart
- Secrets are injected via `pass-cli` — never hardcode them
- Logs are written to `.e2e-logs/` — add this to `.gitignore`
- The runner will attempt up to 5 fix iterations before stopping

### Service Health

- Backend health: `http://localhost:8080` (120s timeout)
- Frontend health: `http://localhost:4321` (60s timeout)

## Detailed Technologies

- **Backend**: Kotlin 2.3.20 / Java 21, Micronaut 4.10, Hibernate JPA, PicoCLI 4.7.7, Jakarta Mail, Apache POI, AWS SDK v2
- **Frontend**: Astro 6.1, React 19, TypeScript, Bootstrap 5.3, Axios
- **Database**: MariaDB 11.4, HikariCP connection pool — schema via Flyway + Hibernate auto-migration (per Constitution VI)
- **Build**: Gradle 9.4.1 (Kotlin DSL)
- **Testing**: JUnit 6, Mockk, Testcontainers, AssertJ, Playwright
- **CLI**: PicoCLI, CrowdStrike Falcon API, AWS SDK v2 (S3)
- **MCP**: Streamable HTTP transport, JSON-RPC 2.0

---

## Behavioral Guidelines

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

*Last updated: 2026-04-27*
