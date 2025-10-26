# Claude Code Agent Context

## Project Overview

**secman** - Security requirement and risk assessment management tool

- Full-stack: Kotlin/Micronaut backend + Astro/React frontend
- Tech: Micronaut 4.10, Kotlin 2.1, Astro 5.14, React 19, MariaDB 12
- Purpose: Manage security requirements, norms, use cases, assets, and vulnerabilities

## Architecture

**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)

### Backend (`src/backendng/`)

- **Layers**: Domain (JPA entities) → Repository (Micronaut Data) → Service → Controller (REST)
- **Security**: JWT auth, OAuth2, RBAC roles: USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION
- **Testing**: JUnit 5 + MockK, TDD mandatory

### Frontend (`src/frontend/`)

- **Framework**: Astro with React islands, Bootstrap 5
- **API**: Axios client, sessionStorage for JWT
- **Testing**: Playwright E2E

### Helper Tools (`src/helper/`)

- **Falcon Tool**: Query CrowdStrike API for vulnerabilities
- **CLI**: `falcon-vulns` with device type/severity filters
- **Auth**: FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_CLOUD_REGION

## Command Line Interface (`src/cli/`)

- **Falcon Tool**: Query CrowdStrike API for vulnerabilities

## Key Entities

### VulnerabilityExceptionRequest (Feature 031)

- **Fields**: vulnerability(FK), scope(SINGLE_VULNERABILITY/CVE_PATTERN), status(PENDING/APPROVED/REJECTED/EXPIRED/CANCELLED), autoApproved, reason(50-2048), expirationDate, requestedByUser(FK), reviewedByUser(FK)
- **State**: PENDING → {APPROVED,REJECTED,CANCELLED}; APPROVED → {EXPIRED,CANCELLED}
- **Services**: VulnerabilityExceptionRequestService (auto-approval, optimistic locking), ExceptionRequestAuditService (async), ExceptionRequestStatisticsService (analytics)
- **API**: 11 endpoints (/api/vulnerability-exception-requests/*)
- **Frontend**: ExceptionRequestModal, MyExceptionRequests, ExceptionApprovalDashboard, SSE badge updates

### UserMapping (Feature 013/016)

- **Fields**: email, awsAccountId(12 digits), domain
- **Validation**: Email format, 12-digit AWS account, domain format
- **Import**: Excel (.xlsx) + CSV (.csv) with auto-delimiter detection, scientific notation parsing
- **Access**: ADMIN only

### Workgroup (Feature 008)

- **Fields**: name, description, users(ManyToMany), assets(ManyToMany)
- **Access Control**: Users see assets from their workgroups + personally created/uploaded
- **CRUD**: ADMIN role only

### Release (Feature 011)

- **Fields**: version(semantic), name, status(DRAFT/PUBLISHED/ARCHIVED), createdBy
- **Snapshots**: RequirementSnapshot entities (immutable point-in-time copies)
- **Access**: ADMIN/RELEASE_MANAGER create/delete; all read

### VulnerabilityException (Feature 004)

- **Fields**: exceptionType(IP/PRODUCT), targetValue, expirationDate, reason
- **Methods**: isActive(), matches(vulnerability, asset)
- **Access**: ADMIN, VULN roles

### Asset (Extended)

- **Core**: id, name, type, ip, owner, description, lastSeen
- **Metadata**: groups, cloudAccountId, cloudInstanceId, adDomain, osVersion
- **Relations**: vulnerabilities(OneToMany), scanResults(OneToMany), workgroups(ManyToMany), manualCreator(FK), scanUploader(FK)

### Vulnerability

- **Fields**: asset(FK), vulnerabilityId(CVE), cvssSeverity, vulnerableProductVersions, daysOpen, scanTimestamp
- **Relations**: ManyToOne Asset (cascade delete)

### User

- **Fields**: username, email, passwordHash, roles(collection), workgroups(ManyToMany)
- **Roles**: USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION

### OutdatedAssetMaterializedView (Feature 034)

- **Purpose**: Fast-loading view of assets with overdue vulnerabilities (>reminder_one_days threshold)
- **Fields**: assetId(FK), assetName, assetType, totalOverdueCount, criticalCount, highCount, mediumCount, lowCount, oldestVulnDays, oldestVulnId, workgroupIds(denormalized), lastCalculatedAt
- **Pattern**: Materialized view - pre-calculated denormalized table refreshed async
- **Performance**: <2s page load for 10,000+ assets via indexed queries
- **Access Control**: ADMIN sees all; VULN sees only workgroup-assigned assets
- **Refresh**: Manual (UI button) + automatic (after CLI imports)

### MaterializedViewRefreshJob (Feature 034)

- **Purpose**: Track async refresh operations with progress monitoring
- **Fields**: status(RUNNING/COMPLETED/FAILED), triggeredBy, assetsProcessed, totalAssets, progressPercentage, startedAt, completedAt, durationMs, errorMessage
- **Pattern**: Background job with @Async execution
- **Progress**: SSE streaming for real-time updates (every 1000 assets)
- **Triggers**: Manual refresh button, CLI vulnerability imports
- **Concurrency**: Single job at a time (prevents concurrent refreshes)

## API Endpoints (Critical Only)

### Import

- `POST /api/import/upload-xlsx` - Requirements
- `POST /api/import/upload-nmap-xml` - Nmap scans
- `POST /api/import/upload-vulnerability-xlsx` - Vulnerabilities
- `POST /api/import/upload-user-mappings` - Excel user mappings (ADMIN)
- `POST /api/import/upload-user-mappings-csv` - CSV user mappings (ADMIN)
- `POST /api/import/upload-assets-xlsx` - Assets import

### Assets

- `GET /api/assets` - List (workgroup-filtered)
- `POST /api/assets` - Create
- `DELETE /api/assets/bulk` - Bulk delete (ADMIN)
- `GET /api/assets/export` - Excel export

### Vulnerabilities

- `GET /api/vulnerabilities/current` - Current vulns (workgroup-filtered for VULN)
- `GET /api/vulnerability-exceptions` - List exceptions (ADMIN, VULN)
- `POST /api/vulnerability-exception-requests` - Create exception request
- `GET /api/vulnerability-exception-requests/pending/count` - Badge count
- `GET /api/exception-badge-updates` - SSE real-time updates

### Outdated Assets (Feature 034)

- `GET /api/outdated-assets` - List outdated assets (pagination, sorting, filtering by severity/search)
- `GET /api/outdated-assets/{id}` - Single asset details (workgroup access control)
- `GET /api/outdated-assets/{id}/vulnerabilities` - Paginated vulnerabilities for asset
- `GET /api/outdated-assets/last-refresh` - Timestamp of last materialized view refresh
- `GET /api/outdated-assets/count` - Count of outdated assets (workgroup-filtered)
- `POST /api/materialized-view-refresh/trigger` - Trigger async refresh (ADMIN only)
- `GET /api/materialized-view-refresh/progress` - SSE stream of refresh progress
- `GET /api/materialized-view-refresh/status` - Current refresh job status
- `GET /api/materialized-view-refresh/history` - Recent refresh job history (last 10)

### Workgroups

- `POST /api/workgroups` - Create (ADMIN)
- `GET /api/workgroups` - List (ADMIN)
- `POST /api/workgroups/{id}/users` - Assign users (ADMIN)
- `POST /api/workgroups/{id}/assets` - Assign assets (ADMIN)

### Releases

- `POST /api/releases` - Create (ADMIN, RELEASE_MANAGER)
- `GET /api/releases` - List with status filter
- `GET /api/releases/compare` - Compare releases

### Auth

- `POST /api/auth/login` - JWT login
- OAuth2 SSO endpoints

## Development Workflow

### TDD (NON-NEGOTIABLE)

1. Contract tests (failing)
2. Unit tests (failing)
3. Implement
4. Refactor
5. Target: ≥80% coverage

### Git

- Commits: `type(scope): description`
- Branches: `###-feature-name`
- PR gates: tests, lint, Docker build

### Commands

```bash
# Backend
./gradlew test build

# Frontend
npm test; npm run dev

```

## Constitutional Principles

1. **Security-First**: File validation, input sanitization, RBAC
2. **TDD**: Tests before implementation
3. **API-First**: RESTful, backward compatible
4. **RBAC**: @Secured on endpoints, role checks in UI
5. **Schema Evolution**: Hibernate auto-migration

## Common Patterns

### CSV/Excel Import

1. Validate file (size ≤10MB, extension, content-type)
2. Parse (Apache POI for Excel, Commons CSV for CSV)
3. Detect delimiter/encoding for CSV (UTF-8 BOM, ISO-8859-1 fallback)
4. Validate headers (case-insensitive)
5. Parse rows (skip invalid, continue valid, handle scientific notation)
6. Check duplicates (DB + file)
7. Batch save
8. Return ImportResult: imported, skipped, errors[]

### Entity Merge (Asset)

1. Find by name
2. Merge if exists (append groups, update IP, preserve owner)
3. Create if new
4. Save

### Authentication

- Endpoints: `@Secured(SecurityRule.IS_AUTHENTICATED)`
- Admin: `authentication.roles.contains("ADMIN")`
- VULN: Check "VULN" or "ADMIN"
- Frontend: JWT in sessionStorage → Axios headers

### Materialized View Refresh (Feature 034)

1. Create MaterializedViewRefreshJob with status=RUNNING
2. Execute @Async refresh method (non-blocking)
3. Delete old materialized view data (truncate table)
4. Query source data with business logic (e.g., overdue threshold)
5. Batch process in chunks (1000 records)
6. Publish progress events via ApplicationEventPublisher
7. Emit SSE events via Sinks.Many.multicast() for real-time UI updates
8. Mark job COMPLETED/FAILED with metrics (duration, processed count)
9. Auto-trigger: After CLI imports (if data changed)
10. Manual trigger: UI refresh button (ADMIN only)

**Performance**: <30s for 10,000 assets, <2s query time via indexed materialized table

## File Locations

- **Backend**: `src/backendng/src/main/kotlin/com/secman/{domain,controller,service,repository}/`
- **Frontend**: `src/frontend/src/{components,pages,services}/`
- **Helper**: `src/helper/src/{models,services,cli,exporters,lib}/`
- **Tests**: `src/backendng/src/test/kotlin/com/secman/{contract,service,integration}/`
- **Config**: `docker-compose.yml`, `.env`, `src/backendng/src/main/resources/application.yml`

---

*Optimized for performance. See git history for detailed feature specs. Last updated: 2025-10-24*
