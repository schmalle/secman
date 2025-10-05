# Claude Code Agent Context

## Project Overview
**secman** - Security requirement and risk assessment management tool
- Full-stack web application (Kotlin/Micronaut backend + Astro/React frontend)
- Purpose: Manage security requirements, norms, use cases, assets, and vulnerabilities
- Tech stack: Micronaut 4.4, Kotlin 2.1, Astro 5.14, React 19, MariaDB 11.4

## Tech Stack
- **Language**: Kotlin 2.1.0 / Java 21, TypeScript/JavaScript (Astro 5.14, React 19)
- **Framework**: Micronaut 4.4, Hibernate JPA, Apache POI 5.3 (Excel), Astro, React, Bootstrap 5.3
- **Database**: MariaDB 11.4 via Hibernate JPA with auto-migration
- **Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
- **Deployment**: Docker Compose (multi-arch: AMD64/ARM64)

## Architecture
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)

### Backend (`src/backendng/`)
- **Domain**: JPA entities (Requirement, Norm, UseCase, Asset, Vulnerability)
- **Repository**: Micronaut Data repositories
- **Service**: Business logic layer
- **Controller**: RESTful APIs (@Controller, @Secured)
- **Security**: JWT authentication, OAuth2, RBAC (USER, ADMIN, VULN roles)

### Frontend (`src/frontend/`)
- **Framework**: Astro with React islands
- **Components**: React .tsx components with Bootstrap 5
- **Routing**: Astro file-based routing
- **API Client**: Axios for backend communication

## Recent Changes
- 007-please-evaluate-if: Updated Micronaut 4.4→4.5.4, Spring Security Crypto 6.3.5→6.4.4 (CVE fixes), Apache POI 5.3.0→5.4.1

### Feature 008: Workgroup-Based Access Control (2025-10-04)
- Added Workgroup domain entity with many-to-many relationships to User and Asset
- Implemented dual ownership tracking: manual creator + scan uploader for assets
- Created WorkgroupService with full CRUD and membership management
- Created WorkgroupController with 9 ADMIN-only endpoints
- Extended AssetFilterService for centralized access control filtering
- Modified AssetController, VulnerabilityManagementController, ScanController for workgroup-based filtering
- Updated UserController to include workgroup information in responses
- Created WorkgroupManagement React component with full CRUD UI
- Added workgroups navigation to Sidebar (ADMIN only)
- Access control: ADMIN sees all, VULN respects workgroups, USER sees workgroup assets + owned assets

### Feature 006: MCP Tools for Security Data (2025-10-04)

### Feature 005: Masscan XML Import (2025-10-04)

### Feature 004: VULN Role & Vulnerability Management UI (2025-10-03)

### Feature 003: Vulnerability Management (2025-10-03)

### Feature 002: Nmap Scan Import (2024-10-03)

### Feature 001: Admin Role Management (2024-10-01)

## Key Entities

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

### Requirement
- **Fields**: id, shortreq, chapter, norm, details, motivation, example, usecase
- **Relationships**: ManyToMany Norm, ManyToMany UseCase
- **Import**: Excel import via /api/import/upload-xlsx

### ScanResult (Feature 002)
- **Fields**: id, asset (FK), port, service, product, version, discoveredAt
- **Relationships**: ManyToOne Asset
- **Source**: Nmap XML import

### User (EXTENDED - Feature 008)
- **Fields**: id, username, email, passwordHash, roles (ElementCollection), workgroups (ManyToMany), createdAt, updatedAt
- **Roles**: USER, ADMIN, VULN
- **Relationships**: workgroups (ManyToMany bidirectional)
- **Access Control**: Users see resources from their workgroups + personally created/uploaded items

## API Endpoints

### Import
- `POST /api/import/upload-xlsx` - Requirements import
- `POST /api/import/upload-nmap-xml` - Nmap scan import (Feature 002)
- `POST /api/import/upload-masscan-xml` - Masscan scan import (Feature 005)
- `POST /api/import/upload-vulnerability-xlsx` - Vulnerability import (Feature 003)

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
- **Services**: `src/backendng/src/main/kotlin/com/secman/service/`
- **Tests**: `src/backendng/src/test/kotlin/com/secman/`

### Frontend
- **Components**: `src/frontend/src/components/`
- **Pages**: `src/frontend/src/pages/`
- **Services**: `src/frontend/src/services/`
- **Tests**: `src/frontend/tests/e2e/`

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

# Docker
docker-compose up -d     # Start all services
docker-compose logs -f   # View logs
docker-compose down      # Stop all services
```

---
*Auto-generated from feature specifications. Last updated: 2025-10-04*
