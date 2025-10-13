# Claude Code Agent Context

## Project Overview
**secman** - Security requirement and risk assessment management tool
- Full-stack web application (Kotlin/Micronaut backend + Astro/React frontend)
- Python helper utilities for external integrations
- Purpose: Manage security requirements, norms, use cases, assets, and vulnerabilities
- Tech stack: Micronaut 4.4, Kotlin 2.1, Astro 5.14, React 19, MariaDB 11.4, Python 3.11+

## Tech Stack
- **Language**: Kotlin 2.1.0 / Java 21, TypeScript/JavaScript (Astro 5.14, React 19), Python 3.11+
- **Framework**: Micronaut 4.4, Hibernate JPA, Apache POI 5.3 (Excel), Apache Commons CSV 1.11.0 (CSV), Astro, React, Bootstrap 5.3
- **Database**: MariaDB 11.4 via Hibernate JPA with auto-migration
- **Helper Tools**: falconpy (CrowdStrike Falcon API), openpyxl (XLSX export), argparse (CLI)
- **Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E), pytest (helper tools)
- **Deployment**: Docker Compose (multi-arch: AMD64/ARM64)

## Architecture
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)

### Backend (`src/backendng/`)
- **Domain**: JPA entities (Requirement, Norm, UseCase, Asset, Vulnerability, Release, RequirementSnapshot)
- **Repository**: Micronaut Data repositories
- **Service**: Business logic layer
- **Controller**: RESTful APIs (@Controller, @Secured)
- **Security**: JWT authentication, OAuth2, RBAC (USER, ADMIN, VULN, RELEASE_MANAGER roles)

### Frontend (`src/frontend/`)
- **Framework**: Astro with React islands
- **Components**: React .tsx components with Bootstrap 5
- **Routing**: Astro file-based routing
- **API Client**: Axios for backend communication

### Helper Tools (`src/helper/`)
- **Purpose**: CLI utilities for external integrations
- **Falcon Vulnerability Tool**: Query CrowdStrike Falcon API for vulnerability data
  - Filters: Device type (CLIENT/SERVER/BOTH), severity levels, days open, AD domain, hostname
  - Export formats: XLSX, CSV, TXT
  - Authentication: Environment-based (FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_CLOUD_REGION)
  - CLI command: `falcon-vulns`
- **Structure**: models/, services/, cli/, exporters/, lib/

## Recent Changes
- 016-i-want-to: CSV-Based User Mapping Upload (2025-10-13) - CSV upload support for email-AWS-domain mappings, parallel to Excel upload (Feature 013), handles scientific notation AWS account IDs, auto-detects CSV delimiter/encoding
- 015-we-have-currently: Added Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19) + Micronaut 4.4, Hibernate JPA, Apache POI 5.3, Astro, React 19, Bootstrap 5.3, HTTP client for CrowdStrike API
- 013-user-mapping-upload: User Mapping with AWS Account & Domain Upload (2025-10-08) - Excel upload for email-AWS-domain mappings, ADMIN-only access, comprehensive validation and duplicate handling, foundation for future RBAC
- 012-build-ui-for: Release Management UI Enhancement (2025-10-07) - Complete UI for release management with browse, create, view details, compare, status lifecycle, delete (RBAC), and export integration

### Feature 016: CSV-Based User Mapping Upload (2025-10-13)

Complete CSV upload support for user mappings, parallel to existing Excel upload (Feature 013):

**Backend Components** (`src/backendng/`):
- **CSVUserMappingParser.kt** (460 lines) - Core CSV parsing service
  - Encoding detection: UTF-8 BOM detection + ISO-8859-1 fallback (no heavy dependencies)
  - Delimiter auto-detection: Comma, semicolon, tab (counts occurrences in first line)
  - Scientific notation parsing: BigDecimal for AWS account IDs (9.98987E+11 → 998987000000)
  - Header validation: Case-insensitive matching (account_id, Account_ID, ACCOUNT_ID all work)
  - Field validation: Email format, 12-digit AWS account ID, domain format
  - Duplicate detection: Within file (HashSet) + database (repository query)
  - Batch persistence: Repository.saveAll() for efficiency
  - Error handling: Skip invalid rows, continue with valid ones, return structured errors

- **ImportController.kt** - CSV endpoints
  - `POST /api/import/upload-user-mappings-csv` - Upload CSV file (ADMIN only)
  - `GET /api/import/user-mapping-template-csv` - Download CSV template (ADMIN only)
  - File validation: 10MB max, .csv extension, empty file check
  - Response: ImportResult { message, imported, skipped, errors[] }

**Frontend Components** (`src/frontend/src/components/`):
- **UserMappingUpload.tsx** (Enhanced) - Dual upload UI for Excel and CSV
  - Side-by-side cards: Excel upload (left) + CSV upload (right)
  - Separate file inputs with proper accept filters (.xlsx vs .csv)
  - Client-side validation: Extension, size (10MB), empty file
  - Loading states: Spinner during upload, disabled buttons
  - Result display: Imported/skipped counts, error details with line numbers
  - Template downloads: Excel and CSV template buttons

**Frontend Services** (`src/frontend/src/services/`):
- **userMappingService.ts** - API wrapper
  - `uploadUserMappingsCSV(file)` - Upload CSV with validation
  - `downloadCSVTemplate()` - Download CSV template with blob handling

**Test Coverage** (Feature 016):
- **Backend Tests**: 46 tests total
  - 20 contract tests (CSVUploadContractTest.kt) - API compliance
  - 26 unit tests (CSVUserMappingParserTest.kt) - Parser logic
- **Test Scenarios**:
  - Delimiter detection (comma, semicolon, tab)
  - Scientific notation parsing (Excel exports)
  - Encoding detection (UTF-8 BOM, ISO-8859-1)
  - Email validation (valid/invalid formats)
  - Account ID validation (12 digits, non-numeric, scientific notation)
  - Domain validation (format, default to "-NONE-")
  - Duplicate detection (within file, existing in DB)
  - Row skipping with structured error reporting
  - Format flexibility (reversed columns, case variations, extra columns)
  - Authentication/authorization (401, 403)
  - File validation (empty, wrong extension, oversized)

**Features Implemented** (3 User Stories):
1. **US1 (P1 - MVP)**: Upload CSV user mappings with validation and duplicate detection
2. **US2 (P2)**: Handle CSV format variations (column order, case-insensitive headers, extra columns)
3. **US3 (P3)**: Download CSV template with example data

**CSV Format Requirements**:
- **Required Columns**: `account_id` (12 digits), `owner_email`
- **Optional Columns**: `domain` (defaults to "-NONE-")
- **Supported Delimiters**: Comma (,), semicolon (;), tab (\t)
- **Header Flexibility**: Case-insensitive, any column order
- **Encoding Support**: UTF-8, ISO-8859-1
- **Special Handling**: Scientific notation for AWS account IDs (Excel exports)
- **Max File Size**: 10 MB
- **Error Behavior**: Skip invalid rows, continue with valid ones, return detailed error list (max 50 errors)

**Statistics**:
- Production code: 460 lines (parser) + 100 lines (controller) + 200 lines (frontend) = 760 lines
- Test code: 46 backend tests (contract + unit)
- Total: ~1,200 lines (production + tests)

### Feature 012: Release Management UI Enhancement (2025-10-07)

Complete user interface for release management with 8 major components:

**Pages**:

**Components** (`src/frontend/src/components/`):

**Services** (`src/frontend/src/services/`):
  - Methods: list(), getById(), create(), updateStatus(), delete(), getSnapshots(), compare()
  - Pagination support, error handling, TypeScript interfaces

**Utilities** (`src/frontend/src/utils/`):
  - canDeleteRelease() - ADMIN deletes any, RELEASE_MANAGER deletes own only
  - canCreateRelease(), canUpdateReleaseStatus(), canViewReleases()
  - getPermissionErrorMessage()
  - Multiple sheets: Summary, Added, Deleted, Modified, Unchanged
  - Color-coded rows, formatted columns, auto-width

**Features Implemented**:
1. **Browse & Search** (P1): List view with status filtering, version/name search, pagination (20/page)
2. **Create Release** (P1): Modal with semantic versioning validation, duplicate detection, RBAC enforcement (ADMIN/RELEASE_MANAGER)
3. **View Details** (P2): Complete metadata display, paginated snapshots (50/page), snapshot detail modal, export buttons
4. **Compare Releases** (P2): Dropdown selectors, summary stats, color-coded sections (Added/Deleted/Modified/Unchanged), field-by-field diff, Excel export
5. **Status Lifecycle** (P2): Publish DRAFT → PUBLISHED, Archive PUBLISHED → ARCHIVED, confirmation modals, RBAC enforcement
6. **Export Integration** (P2): Release selector on export pages, default to "Current", historical exports with releaseId parameter
7. **Delete Release** (P3): RBAC-enforced delete (ADMIN: any release, RELEASE_MANAGER: own releases only), confirmation modal, cascade warning

**Accessibility & Polish** (Phase 8):

**Test Coverage**:

**Statistics**:

### Feature 011: Release-Based Requirement Version Management (2025-10-05)

### Feature 008: Workgroup-Based Access Control (2025-10-04)

### Feature 006: MCP Tools for Security Data (2025-10-04)

### Feature 005: Masscan XML Import (2025-10-04)

### Feature 004: VULN Role & Vulnerability Management UI (2025-10-03)

### Feature 003: Vulnerability Management (2025-10-03)

### Feature 002: Nmap Scan Import (2024-10-03)

### Feature 001: Admin Role Management (2024-10-01)

## Key Entities

### Release (NEW - Feature 011)
- **Fields**: id, version (semantic versioning), name, description, status (DRAFT/PUBLISHED/ARCHIVED), releaseDate, createdBy, createdAt, updatedAt
- **Validation**: Unique version, semantic versioning format (MAJOR.MINOR.PATCH)
- **Relationships**: OneToMany RequirementSnapshot (cascade delete)
- **Access**: ADMIN, RELEASE_MANAGER roles for create/delete; all authenticated users for read

### RequirementSnapshot (NEW - Feature 011)
- **Fields**: id, release (FK), originalRequirementId, all requirement fields (shortreq, chapter, norm, details, motivation, example, usecase), usecaseIdsSnapshot (JSON), normIdsSnapshot (JSON), snapshotTimestamp
- **Purpose**: Immutable point-in-time copy of requirement state
- **Relationships**: ManyToOne Release (cascade delete)
- **Indexes**: release_id, original_requirement_id
- **Factory**: Companion object method `fromRequirement(requirement, release)` for snapshot creation

### UserMapping (NEW - Feature 013)
- **Fields**: id, email, awsAccountId (12 digits), domain, createdAt, updatedAt
- **Validation**: Email format (contains @), AWS account format (exactly 12 digits), domain format (alphanumeric + dots + hyphens)
- **Relationships**: Independent (no FK to User entity - may reference future users)
- **Indexes**: Unique composite (email, awsAccountId, domain), individual indexes on email, awsAccountId, domain, (email + awsAccountId)
- **Access**: ADMIN role only for upload and view
- **Purpose**: Foundation for multi-tenant RBAC across AWS accounts and domains
- **Normalization**: Email and domain stored lowercase for case-insensitive matching

### Workgroup (NEW - Feature 008)
- **Fields**: id, name, description, users (ManyToMany), assets (ManyToMany), createdAt, updatedAt
- **Validation**: Name 1-100 chars, alphanumeric + spaces + hyphens, case-insensitive unique
- **Relationships**: ManyToMany User (bidirectional), ManyToMany Asset (bidirectional)
- **Join Tables**: user_workgroups, asset_workgroups
- **Access**: ADMIN role only for CRUD operations

### VulnerabilityException (NEW - Feature 004)
- **Fields**: id, exceptionType (IP/PRODUCT), targetValue, expirationDate, reason, createdBy, createdAt, updatedAt
- **Methods**: isActive(), matches(vulnerability, asset)
- **Indexes**: exception_type, expiration_date
- **Access**: ADMIN, VULN roles only

### Vulnerability (Feature 003)
- **Fields**: id, asset (FK), vulnerabilityId (CVE), cvssSeverity, vulnerableProductVersions, daysOpen, scanTimestamp, createdAt
- **Relationships**: ManyToOne Asset (bidirectional, cascade delete)
- **Indexes**: asset_id, (asset_id, scan_timestamp)

### Asset (EXTENDED - Feature 003, 008)
- **Workgroup Fields** (Feature 008): workgroups (ManyToMany), manualCreator (FK nullable), scanUploader (FK nullable)
- **Metadata Fields**: groups (comma-separated), cloudAccountId, cloudInstanceId, adDomain, osVersion
- **Core Fields**: id, name, type, ip, owner, description, lastSeen
- **Relationships**: vulnerabilities (OneToMany), scanResults (OneToMany), workgroups (ManyToMany), manualCreator (ManyToOne User), scanUploader (ManyToOne User)
- **Key Methods**: addScanResult(), mergeVulnerabilityData() (planned)
- **Access Control**: Users see assets from their workgroups + assets they created/uploaded (Feature 008)

### Requirement (EXTENDED - Feature 011)
- **Fields**: id, shortreq, chapter, norm, details, motivation, example, usecase
- **Relationships**: ManyToMany Norm, ManyToMany UseCase
- **Import**: Excel import via /api/import/upload-xlsx
- **Deletion**: Prevented if requirement is frozen in any release (Feature 011)

### ScanResult (Feature 002)
- **Fields**: id, asset (FK), port, service, product, version, discoveredAt
- **Relationships**: ManyToOne Asset
- **Source**: Nmap XML import

### User (EXTENDED - Feature 008, 011)
- **Fields**: id, username, email, passwordHash, roles (ElementCollection), workgroups (ManyToMany), createdAt, updatedAt
- **Roles**: USER, ADMIN, VULN, RELEASE_MANAGER (Feature 011)
- **Relationships**: workgroups (ManyToMany bidirectional)
- **Access Control**: Users see resources from their workgroups + personally created/uploaded items

## API Endpoints

### Import
- `POST /api/import/upload-xlsx` - Requirements import
- `POST /api/import/upload-nmap-xml` - Nmap scan import (Feature 002)
- `POST /api/import/upload-masscan-xml` - Masscan scan import (Feature 005)
- `POST /api/import/upload-vulnerability-xlsx` - Vulnerability import (Feature 003)
- `POST /api/import/upload-user-mappings` - User mapping Excel upload (Feature 013, ADMIN only)
  - Request: multipart/form-data with xlsxFile
  - Response: ImportResult { message, imported, skipped, errors[] }
  - Validation: Email format, AWS account (12 digits), domain format
  - Behavior: Skip invalid/duplicate rows, continue with valid ones
- `POST /api/import/upload-user-mappings-csv` - User mapping CSV upload (Feature 016, ADMIN only)
  - Request: multipart/form-data with csvFile
  - Response: ImportResult { message, imported, skipped, errors[] }
  - Validation: Same as Excel upload (email, AWS account, domain)
  - CSV Format: RFC 4180, auto-detects delimiter (comma/semicolon/tab), UTF-8/ISO-8859-1
  - Scientific Notation: Handles AWS account IDs in format 9.98987E+11
  - Behavior: Skip invalid/duplicate rows, continue with valid ones, domain defaults to "-NONE-"

### Assets (UPDATED - Feature 008)
- `GET /api/assets` - List assets (workgroup-filtered: users see their workgroup assets + owned assets)
- `GET /api/assets/{id}` - Asset detail (workgroup access control)
- `POST /api/assets` - Create asset (tracks manual creator for ownership)
- `GET /api/assets/{id}/vulnerabilities` - Asset vulnerabilities (workgroup-filtered)

### Workgroup Management (NEW - Feature 008)
- `POST /api/workgroups` - Create workgroup (ADMIN only)
- `GET /api/workgroups` - List all workgroups (ADMIN only)
- `GET /api/workgroups/{id}` - Get workgroup details (ADMIN only)
- `PUT /api/workgroups/{id}` - Update workgroup (ADMIN only)
- `DELETE /api/workgroups/{id}` - Delete workgroup (ADMIN only)
- `POST /api/workgroups/{id}/users` - Assign users to workgroup (ADMIN only)
- `DELETE /api/workgroups/{workgroupId}/users/{userId}` - Remove user from workgroup (ADMIN only)
- `POST /api/workgroups/{id}/assets` - Assign assets to workgroup (ADMIN only)
- `DELETE /api/workgroups/{workgroupId}/assets/{assetId}` - Remove asset from workgroup (ADMIN only)

### Vulnerability Management (UPDATED - Feature 004, 008)
- `GET /api/vulnerabilities/current` - Current vulnerabilities (ADMIN sees all, VULN respects workgroups)
- `GET /api/vulnerability-exceptions` - List exceptions (ADMIN, VULN)
- `POST /api/vulnerability-exceptions` - Create exception (ADMIN, VULN)
- `PUT /api/vulnerability-exceptions/{id}` - Update exception (ADMIN, VULN)
- `DELETE /api/vulnerability-exceptions/{id}` - Delete exception (ADMIN, VULN)

### Scans (UPDATED - Feature 008)
- `GET /api/scans` - List scans (workgroup-filtered: users see scans from workgroup members)

### Release Management (NEW - Feature 011)
- `POST /api/releases` - Create release (ADMIN, RELEASE_MANAGER)
- `GET /api/releases?status=PUBLISHED` - List releases with optional status filter (authenticated)
- `GET /api/releases/{id}` - Get release details (authenticated)
- `DELETE /api/releases/{id}` - Delete release (ADMIN, RELEASE_MANAGER)
- `GET /api/releases/{id}/requirements` - Get release snapshots (authenticated)
- `GET /api/releases/compare?fromReleaseId={id}&toReleaseId={id}` - Compare two releases (authenticated)

### Requirements Export (UPDATED - Feature 011)
- `GET /api/requirements/export/xlsx?releaseId={id}` - Export to Excel (optional release parameter)
- `GET /api/requirements/export/docx?releaseId={id}` - Export to Word (optional release parameter)
- `GET /api/requirements/export/xlsx/translated/{lang}?releaseId={id}` - Export translated Excel
- `GET /api/requirements/export/docx/translated/{lang}?releaseId={id}` - Export translated Word

### Authentication
- `POST /api/auth/login` - JWT login
- OAuth2 endpoints for SSO

### MCP Tools (Feature 006)
Model Context Protocol tools for AI assistant integration:
- `get_assets` - Retrieve asset inventory with filtering (name, type, ip, owner, group) and pagination
- `get_scans` - Retrieve scan history with filtering (scanType, uploadedBy, dateRange) and pagination
- `get_vulnerabilities` - Retrieve vulnerabilities with filtering (cveId, severity, assetId, dateRange) and pagination
- `search_products` - Search products/services discovered in scans, grouped by service+version
- `get_asset_profile` - Get comprehensive asset profile (details, vulnerabilities, scan history, ports)

**Limits**: Max 500 items/page, 50K total results per query
**Rate Limits**: 1000 requests/minute, 50K requests/hour per API key
**Permissions**: ASSETS_READ, SCANS_READ, VULNERABILITIES_READ

## Development Workflow

### TDD (NON-NEGOTIABLE)
1. Write contract tests (failing)
2. Write unit tests (failing)
3. Implement to make tests pass
4. Refactor
5. Coverage target: e80%

### Git Workflow
- Conventional commits: `type(scope): description`
- Feature branches: `###-feature-name` pattern
- PR requires: tests pass, lint pass, Docker build success

### Testing
- **Backend**: `./gradlew test` (JUnit 5 + MockK)
- **Frontend**: `npm test` (Playwright E2E)
- **E2E**: `npm run test:e2e`

## Constitutional Principles (v1.0.0)

**Full Constitution**: See `.specify/memory/constitution.md` for complete details, rationale, and governance.

1. **Security-First**: File validation, input sanitization, RBAC enforced
2. **TDD**: Tests before implementation (Red-Green-Refactor)
3. **API-First**: RESTful with OpenAPI, backward compatibility
4. **Docker-First**: Containerized, .env config, multi-arch
5. **RBAC**: @Secured on endpoints, role checks in UI
6. **Schema Evolution**: Hibernate auto-migration, DB constraints

## Common Patterns

### Excel Import Pattern
1. Validate file (size, format, content-type)
2. Parse with Apache POI XSSFWorkbook
3. Validate headers, map columns
4. Parse rows in try-catch (skip invalid, continue valid)
5. Return counts: imported, skipped, assetsCreated

### CSV Import Pattern (Feature 016)
1. Validate file (size ≤10MB, .csv extension, content-type)
2. Detect encoding (UTF-8 BOM or default UTF-8 with ISO-8859-1 fallback)
3. Detect delimiter (comma/semicolon/tab from first line)
4. Parse with Apache Commons CSV (RFC 4180 compliant)
5. Validate headers (case-insensitive: account_id, owner_email, optional domain)
6. For each row:
   - Parse account ID (handle scientific notation with BigDecimal)
   - Validate fields (email format, 12-digit account ID, domain format)
   - Normalize (lowercase email/domain, trim whitespace)
   - Check for duplicates (database + within file)
   - Create entity or skip with reason
7. Batch save valid entities
8. Return ImportResult: imported count, skipped count, errors[]

### Entity Merge Pattern (Asset)
1. Find existing by hostname: `assetRepository.findByName(hostname)`
2. If exists: Merge (append groups, update IP, preserve owner/type/description)
3. If not exists: Create with defaults
4. Save and return

### Authentication
- All API endpoints: `@Secured(SecurityRule.IS_AUTHENTICATED)`
- Admin-only: Check `authentication.roles.contains("ADMIN")`
- VULN role: Check `authentication.roles.contains("VULN")` or `authentication.roles.contains("ADMIN")`
- Frontend: Store JWT in sessionStorage, add to Axios headers

## File Locations

### Backend
- **Domain**: `src/backendng/src/main/kotlin/com/secman/domain/`
- **Controllers**: `src/backendng/src/main/kotlin/com/secman/controller/`
  - `ImportController.kt` - Data import endpoints (xlsx, nmap, masscan, vulnerabilities, user mappings)
- **Services**: `src/backendng/src/main/kotlin/com/secman/service/`
  - `CSVUserMappingParser.kt` - CSV parsing for user mappings (Feature 016)
- **Tests**: `src/backendng/src/test/kotlin/com/secman/`
  - `contract/CSVUploadContractTest.kt` - Contract tests for CSV upload endpoint (Feature 016)
  - `service/CSVUserMappingParserTest.kt` - Unit tests for CSV parser (Feature 016)

### Frontend
- **Components**: `src/frontend/src/components/`
- **Pages**: `src/frontend/src/pages/`
- **Services**: `src/frontend/src/services/`
- **Tests**: `src/frontend/tests/e2e/`

### Helper Tools
- **Root**: `src/helper/`
- **Models**: `src/helper/src/models/`
- **Services**: `src/helper/src/services/`
- **CLI**: `src/helper/src/cli/`
- **Exporters**: `src/helper/src/exporters/`
- **Utilities**: `src/helper/src/lib/`
- **Tests**: `src/helper/tests/` (contract/, integration/, unit/)

### Config
- **Docker**: `docker-compose.yml`
- **Env**: `.env` (not committed)
- **Backend Config**: `src/backendng/src/main/resources/application.yml`

## Quick Commands
```bash
# Backend
./gradlew build          # Build backend
./gradlew test           # Run tests
./gradlew run            # Run locally

# Frontend
npm run dev              # Dev server (port 4321)
npm run build            # Production build
npm test                 # E2E tests

# Helper Tools (Falcon API)
cd src/helper
pip install -r requirements.txt  # Install dependencies
pip install -e .                 # Install in editable mode
falcon-vulns --device-type SERVER --severity CRITICAL --min-days-open 30  # Query vulnerabilities
pytest tests/                    # Run tests
ruff check .                     # Run linter

# Docker
docker-compose up -d     # Start all services
docker-compose logs -f   # View logs
docker-compose down      # Stop all services
```

---
*Auto-generated from feature specifications. Last updated: 2025-10-13*
