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

**Outdated Assets (034)**: GET /api/outdated-assets[/{id}[/vulnerabilities]], GET /api/outdated-assets/{last-refresh,count}, POST /api/materialized-view-refresh/trigger (ADMIN), GET /api/materialized-view-refresh/{progress (SSE), status, history}

**Notifications (035)**: GET/PUT /api/notification-preferences, GET /api/notification-logs, GET /api/notification-logs/export (ADMIN)

**Workgroups**: POST/GET /api/workgroups (ADMIN), POST /api/workgroups/{id}/{users,assets} (ADMIN)

**Releases**: POST /api/releases (ADMIN/RELEASE_MANAGER), GET /api/releases, GET /api/releases/compare

**Auth**: POST /api/auth/login, GET /oauth/{authorize,callback}

**User Mappings (042)**: GET /api/user-mappings/{current,applied-history} (ADMIN), POST/PUT/DELETE /api/user-mappings[/{id}] (ADMIN)

**Identity Providers (041)**: GET /api/identity-providers[/{enabled,{id}}], POST/PUT/DELETE /api/identity-providers[/{id}], POST /api/identity-providers/{id}/test

**Maintenance Banners (047)**: GET /api/maintenance-banners/active (PUBLIC), GET /api/maintenance-banners (ADMIN), GET/POST /api/maintenance-banners[/{id}] (ADMIN), PUT/DELETE /api/maintenance-banners/{id} (ADMIN)

## Development

**Git**: Commits `type(scope): description`, Branches `###-feature-name`

**Commands**:
- Backend: `./gradlew build`
- Frontend: `npm run dev`
- CLI Notifications: `./gradlew cli:run --args='send-notifications [--dry-run] [--verbose] [--outdated-only]'`
- CLI User Mappings (049): `./gradlew cli:run --args='manage-user-mappings <subcommand>'` (see `src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md`)

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
- Frontend: JWT in sessionStorage → Axios headers

### Duplicate Prevention Pattern (Feature 048)
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

### Event-Driven Architecture (Feature 042)
**Pattern**: @EventListener @Async for cross-service communication
```kotlin
@Serdeable data class UserCreatedEvent(val user: User, val source: String)
eventPublisher.publishEvent(UserCreatedEvent(savedUser, "MANUAL"))
@EventListener @Async open fun onUserCreated(event: UserCreatedEvent) { applyFutureUserMapping(event.user) }
```
**Use**: User creation → mapping application, Asset import → view refresh, Vuln detection → email
**Performance**: <5ms event delivery, non-blocking


## File Locations
- Backend: `src/backendng/src/main/kotlin/com/secman/{domain,controller,service,repository}/`
- Frontend: `src/frontend/src/{components,pages,services}/`
- CLI: `src/cli/src/main/kotlin/com/secman/cli/{commands,service}/`
- Email Templates: `src/backendng/src/main/resources/email-templates/`
- Config: `src/backendng/src/main/resources/application.yml`

---
*Last updated: 2025-11-09*

## Active Technologies
- Kotlin 2.2.21 / Java 21 + Micronaut 4.10, Hibernate JPA, JavaMail API (SMTP) (046-oidc-default-roles)
- MariaDB 12 with Hibernate auto-migration (046-oidc-default-roles)
- Kotlin 2.2.21 / Java 21 (backend), JavaScript/TypeScript (frontend with Astro 5.15 + React 19) + Micronaut 4.10, Hibernate JPA (backend), Astro 5.15, React 19, Bootstrap 5.3 (frontend), Axios (API client) (047-maintenance-popup)
- MariaDB 12 (MaintenanceBanner entity with JPA) (047-maintenance-popup)
- Kotlin 2.2.21 / Java 21 + Micronaut 4.10, Hibernate JPA, MariaDB 12 (048-prevent-duplicate-vulnerabilities)
- MariaDB 12 (existing Vulnerability and Asset entities) (048-prevent-duplicate-vulnerabilities)
- Kotlin 2.2.21 / Java 21 + Micronaut 4.10, Hibernate JPA, Picocli 4.7 (CLI framework), Apache Commons CSV 1.11.0, Jackson (JSON parsing) (049-cli-user-mappings)
- MariaDB 12 (reuses existing UserMapping entity from feature 042) (049-cli-user-mappings)

## Recent Changes
- 048-prevent-duplicate-vulnerabilities: Fixed critical 99% data loss bug by removing JPA cascade from Asset.vulnerabilities; added transactional replace pattern for duplicate prevention, comprehensive documentation
- 046-oidc-default-roles: Added Kotlin 2.2.21 / Java 21 + Micronaut 4.10, Hibernate JPA, JavaMail API (SMTP)
