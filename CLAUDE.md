# Claude Code Agent Context

## Project Overview

**secman** - Security requirement and risk assessment management tool

**Stack**: Kotlin 2.2.21 / Java 21, Micronaut 4.10, Hibernate JPA | Astro 5.15, React 19, Bootstrap 5.3 | MariaDB 12, Gradle 9.2

**Architecture**:
- Backend: `src/backendng/` - Domain (JPA) → Repository → Service → Controller (REST)
- Frontend: `src/frontend/` - Astro + React islands, Axios, sessionStorage JWT
- CLI: `src/cli/` - CrowdStrike API queries, notification emails
- Security: JWT auth, OAuth2/OIDC, RBAC (USER, ADMIN, VULN, RELEASE_MANAGER, REQADMIN, SECCHAMPION)

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

**Vulnerabilities**: GET /api/vulnerabilities/current, POST /api/vulnerabilities/export (start job), GET /api/vulnerabilities/export/{jobId}/status, GET /api/vulnerabilities/export/{jobId}/download, DELETE /api/vulnerabilities/export/{jobId} (cancel), GET /api/vulnerabilities/export/history (ADMIN/VULN/SECCHAMPION), GET /api/vulnerability-exceptions, POST /api/vulnerability-exception-requests, GET /api/vulnerability-exception-requests/pending/count, GET /api/exception-badge-updates (SSE)

**Outdated Assets**: GET /api/outdated-assets[/{id}[/vulnerabilities]], GET /api/outdated-assets/{last-refresh,count}, POST /api/materialized-view-refresh/trigger (ADMIN), GET /api/materialized-view-refresh/{progress (SSE), status, history}

**Notifications**: GET/PUT /api/notification-preferences, GET /api/notification-logs, GET /api/notification-logs/export (ADMIN)

**Workgroups**: POST/GET /api/workgroups (ADMIN), POST /api/workgroups/{id}/{users,assets} (ADMIN)

**Releases**: POST /api/releases (ADMIN/REQADMIN), GET /api/releases, GET /api/releases/compare, DELETE /api/releases/{id} (ADMIN/REQADMIN)
- Statuses: PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED
- MCP tools: `list_releases`, `get_release`, `create_release`, `delete_release`, `set_release_status`, `compare_releases` (create/delete require ADMIN/REQADMIN; status management requires ADMIN/RELEASE_MANAGER; all require User Delegation)

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
6. A feature is only complete if gradlew build is showing no errors anymore
7. **Mandatory Security Review**: Every code change MUST undergo a security review before merge. Review covers OWASP Top 10, input validation, authentication/authorization, data exposure, and injection vectors. Document findings in the PR description.
8. **Mandatory Documentation**: Every code change MUST be documented. Update CLAUDE.md (entities, endpoints, patterns), relevant docs/ files, and inline code comments where logic is non-obvious. API changes require updated endpoint documentation. A change without documentation is incomplete.
9. **Mandatory Test Script**: Every code change MUST include a corresponding test script (shell script, curl-based, or e2e) in `scripts/test/` that validates the change works end-to-end. The script must be runnable standalone and document expected inputs/outputs.
10. **Mandatory MCP Availability**: Every new or changed backend function that exposes data or performs actions MUST be made available as an MCP tool. Update the MCP controller, register the tool with proper permissions, and document it in `docs/MCP.md`.

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

---
*Last updated: 2025-12-29*

## Active Technologies
- Kotlin 2.2.21 / Java 21 (backend), TypeScript/React 19 (frontend) + Micronaut 4.10, Hibernate JPA, Axios, Bootstrap 5.3 (058-ai-norm-mapping)
- MariaDB 11.4 (existing `requirement`, `norm`, `requirement_norm` tables) (058-ai-norm-mapping)
- Kotlin 2.2.21 / Java 21 + Micronaut 4.10, Hibernate JPA (060-mcp-list-users)
- MariaDB 11.4 (existing `users` table) (060-mcp-list-users)
- MariaDB 11.4 (existing tables: `vulnerability_exception_request`, `vulnerability_exception`, `outdated_asset_materialized_view`) (062-mcp-vuln-exceptions)
- Kotlin 2.3.0 / Java 25 (backend), Bash (test script) + Micronaut 4.10, Hibernate JPA, PicoCLI (CLI) (063-e2e-vuln-exception)
- Kotlin 2.3.0 / Java 25 + Micronaut 4.10, Hibernate JPA, PicoCLI (CLI), MCP Protocol (064-mcp-cli-user-mapping)
- MariaDB 11.4 (existing `user_mapping` table) (064-mcp-cli-user-mapping)
- Kotlin 2.3.0 / Java 21 + Micronaut 4.10, PicoCLI 4.7.5, AWS SDK for Java v2 (S3) (065-s3-user-mapping-import)
- MariaDB 11.4 (existing user_mapping table), local temp files for S3 downloads (065-s3-user-mapping-import)
- Kotlin 2.3.0 / Java 25 + Micronaut 4.10, Hibernate JPA, Apache POI 5.3 (Excel/Word exports) (066-requirement-versioning)
- MariaDB 11.4 (existing `requirement`, `requirement_snapshot`, `releases` tables) (066-requirement-versioning)
- Kotlin 2.3.0 / Java 25 + Micronaut 4.10, PicoCLI (CLI framework), Jakarta Mail (email) (070-admin-summary-email)
- MariaDB 11.4 (existing database) (070-admin-summary-email)
- MariaDB 11.4 (read-only queries for statistics) (069-enhanced-admin-summary)
- Kotlin 2.3.0 / Java 25 + Micronaut 4.10, Jakarta Mail (angus-mail 2.0.5), Hibernate JPA (071-ses-smtp-rewrite)
- MariaDB 11.4 (existing `email_configs` table) (071-ses-smtp-rewrite)
- Kotlin 2.3.0 / Java 25 + Micronaut 4.10, Hibernate JPA, Apache POI 5.3 (SXSSFWorkbook) (073-memory-optimization)
- MariaDB 11.4 (HikariCP connection pool, max 20 connections) (073-memory-optimization)
- Kotlin 2.3.0 / Java 25 (backend), Bash (test script), TypeScript/React 19 (frontend) + Micronaut 4.10, Hibernate JPA, curl, jq, 1Password CLI v2.x (074-mcp-e2e-test)
- MariaDB 11.4 (existing tables: users, assets, vulnerabilities, workgroups, user_workgroups, asset_workgroups) (074-mcp-e2e-test)
- TypeScript / React 19 + Astro 5.15, React 19, Bootstrap 5.3 (075-sort-empty-accounts)
- N/A (no data model changes) (075-sort-empty-accounts)
- Kotlin 2.3.0 / Java 25 (backend), TypeScript / React 19 (frontend) + Micronaut 4.10, Hibernate JPA, Astro 5.15, Bootstrap 5.3, Axios (078-release-rework)
- MariaDB 11.4 (existing `releases` table, `requirement_snapshot` table) (078-release-rework)
- Kotlin 2.3.0 / Java 25 (backend), TypeScript / React 19 (frontend), Bash (e2e test) + Micronaut 4.10, Hibernate JPA, Astro 5.15, Bootstrap 5.3 (079-reqadmin-release-role)
- MariaDB 11.4 (no schema changes needed - authorization-only change) (079-reqadmin-release-role)
- MariaDB 11.4 (existing `user_roles` table, no schema changes) (080-default-user-roles)

## Recent Changes
- 058-ai-norm-mapping: Added Kotlin 2.2.21 / Java 21 (backend), TypeScript/React 19 (frontend) + Micronaut 4.10, Hibernate JPA, Axios, Bootstrap 5.3
