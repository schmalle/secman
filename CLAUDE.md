# Claude Code Agent Context

## Project Overview

**secman** - Security requirement and risk assessment management tool

**Stack**: Kotlin 2.3.20 / Java 21, Micronaut 4.10, Hibernate JPA | Astro 6.0, React 19, Bootstrap 5.3 | MariaDB 12, Gradle 9.4

**Architecture**:
- Backend: `src/backendng/` - Domain (JPA) → Repository → Service → Controller (REST)
- Frontend: `src/frontend/` - Astro + React islands, Axios, HttpOnly cookie JWT
- CLI: `src/cli/` - CrowdStrike API queries, notification emails
- Security: JWT auth, OAuth2/OIDC, RBAC (USER, ADMIN, VULN, RELEASE_MANAGER, REQADMIN, SECCHAMPION, REPORT)
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


## API Endpoints

**Import**: POST /api/import/{upload-xlsx, upload-nmap-xml, upload-vulnerability-xlsx, upload-user-mappings, upload-user-mappings-csv, upload-assets-xlsx}

**Assets**: GET/POST /api/assets, DELETE /api/assets/bulk (ADMIN), GET /api/assets/export

**Vulnerabilities**: GET /api/vulnerabilities/current, POST /api/vulnerabilities/export (start job), GET /api/vulnerabilities/export/{jobId}/status, GET /api/vulnerabilities/export/{jobId}/download, DELETE /api/vulnerabilities/export/{jobId} (cancel), GET /api/vulnerabilities/export/history (ADMIN/VULN/SECCHAMPION), GET /api/vulnerability-exceptions, POST /api/vulnerability-exception-requests, GET /api/vulnerability-exception-requests/pending/count, GET /api/exception-badge-updates (SSE)

**Outdated Assets**: GET /api/outdated-assets[/{id}[/vulnerabilities]], GET /api/outdated-assets/{last-refresh,count}, POST /api/materialized-view-refresh/trigger (ADMIN), GET /api/materialized-view-refresh/{progress (SSE), status, history}

**Notifications**: GET/PUT /api/notification-preferences, GET /api/notification-logs, GET /api/notification-logs/export (ADMIN)

**Workgroups**: POST/GET /api/workgroups (ADMIN), POST /api/workgroups/{id}/{users,assets} (ADMIN)

**Releases**: POST /api/releases (ADMIN/REQADMIN), GET /api/releases, GET /api/releases/compare, DELETE /api/releases/{id} (ADMIN/REQADMIN)
- Statuses: PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED
- MCP tools: `list_releases`, `get_release`, `create_release`, `delete_release`, `set_release_status`, `compare_releases` (create/delete require ADMIN/REQADMIN; status management requires ADMIN/RELEASE_MANAGER; all require User Delegation)

**Auth**: POST /api/auth/login, GET /oauth/{authorize,callback}

**User Mappings**: GET /api/user-mappings/{current,applied-history} (ADMIN), POST/PUT/DELETE /api/user-mappings[/{id}] (ADMIN)

**AWS Account Sharing**: GET/POST /api/aws-account-sharing (ADMIN), DELETE /api/aws-account-sharing/{id} (ADMIN)
- MCP tools: `list_aws_account_sharing`, `create_aws_account_sharing`, `delete_aws_account_sharing` (all require ADMIN + User Delegation)

**Identity Providers**: GET /api/identity-providers[/{enabled,{id}}], POST/PUT/DELETE /api/identity-providers[/{id}], POST /api/identity-providers/{id}/test

**Maintenance Banners**: GET /api/maintenance-banners/active (PUBLIC), GET /api/maintenance-banners (ADMIN), GET/POST /api/maintenance-banners[/{id}] (ADMIN), PUT/DELETE /api/maintenance-banners/{id} (ADMIN)

**User Profile**: GET /api/users/profile, PUT /api/users/profile/change-password (LOCAL users only), GET /api/users/profile/mfa-status, PUT /api/users/profile/mfa-toggle

**CLI Add Vulnerability**: POST /api/vulnerabilities/cli-add (ADMIN/VULN) - Add or update vulnerability with auto-asset creation

## Development

**Git**: Commits `type(scope): description`, Branches `###-feature-name`

**Commands**:
- Backend: `./gradlew build`
- Frontend: `npm run dev`
- CLI: Build JAR once with `./gradlew :cli:shadowJar`, then use `./bin/secman <command>`
  - `./bin/secman help` - Show all commands and options
  - `./bin/secman query servers --dry-run` - Query CrowdStrike
  - `./bin/secman send-notifications --dry-run` - Email notifications
  - `./bin/secman manage-user-mappings --help` - User mappings
  - `./bin/secman export-requirements --format xlsx` - Export requirements
  - `./bin/secman add-requirement --shortreq "text"` - Add requirement
  - `./bin/secman add-vulnerability --hostname host --cve CVE-xxx --criticality HIGH` - Add vulnerability

**Test Commands**:
- All tests: `./gradlew build` (includes unit + integration tests)
- Unit tests only: `./gradlew :backendng:test --tests "*ServiceTest*"`
- CLI tests: `./gradlew :cli:test`
- Integration tests (requires Docker): `./gradlew :backendng:test --tests "*IntegrationTest*"`
- Specific test class: `./gradlew :backendng:test --tests "VulnerabilityServiceTest"`
- E2E tests (Playwright): Setup: `cd tests/e2e && npm install && npx playwright install chrome msedge`
- E2E tests (run with 1Password): `./tests/e2e/run-e2e.sh`
- E2E tests (run manually): `cd tests/e2e && SECMAN_BASE_URL=http://localhost:4321 SECMAN_ADMIN_NAME=... SECMAN_ADMIN_PASS=... SECMAN_USER_USER=... SECMAN_USER_PASS=... npx playwright test`

**Principles**:
1. Security-First: File validation, input sanitization, RBAC, security review required
2. API-First: RESTful, backward compatible
3. RBAC: @Secured on endpoints, role checks in UI
4. Schema Evolution: Hibernate auto-migration
5. Never write testcases
6. A feature is only complete. if gradlew build is showing no errors anymore

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
- Frontend: JWT in HttpOnly cookie (`authToken`) → sent automatically with requests
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


# E2E Test Runner Integration

## Quick Start

Run `/e2e-runner` to start the full E2E test loop. This will:
1. Start the Micronaut backend and Astro frontend
2. Run the E2E test script
3. Automatically fix failures and retry

## Architecture


- **E2E Tests**: Shell script at `./scripts/e2e-test.sh`

## E2E Runner Rules

- Backend changes (Kotlin) always require a backend restart
- Frontend changes usually hot-reload via Vite — no restart needed
- Config changes (`astro.config.mjs`, `application.yml`) require restart
- Secrets are injected via `op run` — never hardcode them
- Logs are written to `.e2e-logs/` — add this to `.gitignore`
- The runner will attempt up to 5 fix iterations before stopping

## Service Health

- Backend health: `http://localhost:8080` (120s timeout)
- Frontend health: `http://localhost:4321` (60s timeout)


---
*Last updated: 2026-03-31*

## Active Technologies
- **Backend**: Kotlin 2.3.20 / Java 21, Micronaut 4.10, Hibernate JPA, PicoCLI 4.7.7, Jakarta Mail, Apache POI, AWS SDK v2
- **Frontend**: Astro 6.0, React 19, TypeScript, Bootstrap 5.3, Axios
- **Database**: MariaDB 12, HikariCP connection pool
- **Build**: Gradle 9.4.0 (Kotlin DSL)
- **Testing**: JUnit 6, Mockk, Testcontainers, AssertJ, Playwright
- **CLI**: PicoCLI, CrowdStrike Falcon API, AWS SDK v2 (S3)
- **MCP**: Streamable HTTP transport, JSON-RPC 2.0

## Recent Changes
- 058-ai-norm-mapping: Added Kotlin 2.3.20 / Java 21 (backend), TypeScript/React 19 (frontend) + Micronaut 4.10, Hibernate JPA, Axios, Bootstrap 5.3
