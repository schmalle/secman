# Claude Code Agent Context

## Project Overview

**secman** - Security requirement and risk assessment management tool

**Stack**: Kotlin 2.2.21 / Java 21, Micronaut 4.10, Hibernate JPA | Astro 5.15, React 19, Bootstrap 5.3 | MariaDB 12, Gradle 9.2

**Architecture**:
- Backend: `src/backendng/` - Domain (JPA) → Repository → Service → Controller (REST)
- Frontend: `src/frontend/` - Astro + React islands, Axios, sessionStorage JWT
- CLI: `src/cli/` - CrowdStrike API queries, notification emails
- Security: JWT auth, OAuth2/OIDC, RBAC (USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION)

## Key Entities

### Asset
- **Core**: id, name, type, ip, owner, description, lastSeen
- **Metadata**: groups, cloudAccountId, cloudInstanceId, adDomain, osVersion
- **Relations**: vulnerabilities, scanResults, workgroups, manualCreator, scanUploader

## Unified Access Control

Users access assets if **ANY** is true:
1. User has ADMIN role (universal)
2. Asset in user's workgroup
3. Asset manually created by user
4. Asset discovered via user's scan upload
5. Asset's cloudAccountId matches user's AWS mappings (UserMapping)
6. Asset's adDomain matches user's domain mappings (case-insensitive, UserMapping)


## API Endpoints

**Import**: POST /api/import/{upload-xlsx, upload-nmap-xml, upload-vulnerability-xlsx, upload-user-mappings, upload-user-mappings-csv, upload-assets-xlsx}

**Assets**: GET/POST /api/assets, DELETE /api/assets/bulk (ADMIN), GET /api/assets/export

**Vulnerabilities**: GET /api/vulnerabilities/current, GET /api/vulnerability-exceptions, POST /api/vulnerability-exception-requests, GET /api/vulnerability-exception-requests/pending/count, GET /api/exception-badge-updates (SSE)

**Outdated Assets**: GET /api/outdated-assets[/{id}[/vulnerabilities]], GET /api/outdated-assets/{last-refresh,count}, POST /api/materialized-view-refresh/trigger (ADMIN), GET /api/materialized-view-refresh/{progress (SSE), status, history}

**Notifications**: GET/PUT /api/notification-preferences, GET /api/notification-logs, GET /api/notification-logs/export (ADMIN)

**Workgroups**: POST/GET /api/workgroups (ADMIN), POST /api/workgroups/{id}/{users,assets} (ADMIN)

**Releases**: POST /api/releases (ADMIN/RELEASE_MANAGER), GET /api/releases, GET /api/releases/compare

**Auth**: POST /api/auth/login, GET /oauth/{authorize,callback}

**User Mappings**: GET /api/user-mappings/{current,applied-history} (ADMIN), POST/PUT/DELETE /api/user-mappings[/{id}] (ADMIN)

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
**Stack**: JUnit 5, Mockk, Testcontainers (MariaDB), AssertJ
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

## File Locations
- Backend: `src/backendng/src/main/kotlin/com/secman/{domain,controller,service,repository,config}/`
- Frontend: `src/frontend/src/{components,pages,services}/`
- CLI: `src/cli/src/main/kotlin/com/secman/cli/{commands,service}/`
- Email Templates: `src/backendng/src/main/resources/email-templates/`
- Config: `src/backendng/src/main/resources/application.yml`
- Environment Docs: `docs/ENVIRONMENT.md`

---
*Last updated: 2025-12-29*

## Active Technologies
- Kotlin 2.2.21 / Java 21 (backend), TypeScript/React 19 (frontend) + Micronaut 4.10, Hibernate JPA, Axios, Bootstrap 5.3 (058-ai-norm-mapping)
- MariaDB 11.4 (existing `requirement`, `norm`, `requirement_norm` tables) (058-ai-norm-mapping)
- Kotlin 2.2.21 / Java 21 + Micronaut 4.10, Hibernate JPA (060-mcp-list-users)
- MariaDB 11.4 (existing `users` table) (060-mcp-list-users)
- MariaDB 11.4 (existing tables: `vulnerability_exception_request`, `vulnerability_exception`, `outdated_asset_materialized_view`) (062-mcp-vuln-exceptions)
- Kotlin 2.3.0 / Java 25 (backend), Bash (test script) + Micronaut 4.10, Hibernate JPA, PicoCLI (CLI) (063-e2e-vuln-exception)

## Recent Changes
- 058-ai-norm-mapping: Added Kotlin 2.2.21 / Java 21 (backend), TypeScript/React 19 (frontend) + Micronaut 4.10, Hibernate JPA, Axios, Bootstrap 5.3
