# Implementation Plan: Account Vulns - AWS Account-Based Vulnerability Overview

**Branch**: `018-under-vuln-management` | **Date**: 2025-10-14 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/018-under-vuln-management/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Add a new "Account Vulns" view under Vuln Management that allows non-admin users to view vulnerabilities for assets in their mapped AWS accounts. The view displays assets grouped by AWS account (for users with multiple mappings), sorted by vulnerability count, with per-account pagination. Admin users see a redirect message directing them to use the existing System Vulns view instead.

**Technical Approach**: Backend adds new GET endpoint to query assets filtered by user's AWS account mappings (from user_mapping table), with vulnerability counts aggregated per asset. Frontend creates new Astro page with React components for account grouping, asset tables, and per-account pagination. Extends existing navigation with role-aware menu styling.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Astro 5.14, React 19, Bootstrap 5.3
**Storage**: MariaDB 11.4 (existing tables: user_mapping, assets, vulnerabilities, users)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Web application (Linux server + modern browsers)
**Project Type**: web (backend + frontend)
**Performance Goals**: Page load <3s for 100 assets, responsive for 500 assets across 50 AWS accounts
**Constraints**: AWS account mapping is primary access control (workgroup restrictions do not apply), per-account pagination threshold of 20 assets
**Scale/Scope**: Designed for users with 1-50 AWS account mappings, each account containing 1-100 assets

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅ PASS
- **RBAC Enforcement**: JWT authentication required (@Secured(SecurityRule.IS_AUTHENTICATED)), admin role check at controller + UI level
- **Input Validation**: Email validated via existing user authentication, AWS account IDs validated as 12-digit strings (existing constraint)
- **Data Filtering**: Assets filtered by cloudAccountId matching user's AWS account mappings (no injection risk - parameterized queries)
- **No Sensitive Data Exposure**: Vulnerability counts are aggregated (no CVE details exposed in list view)

### II. Test-Driven Development (NON-NEGOTIABLE) ✅ PASS
- **Contract Tests Required**: New GET endpoint /api/account-vulns (contract tests for auth, role checks, pagination, grouping)
- **Unit Tests Required**: Service layer logic (AWS account filtering, vulnerability counting, sorting, pagination)
- **E2E Tests Required**: Frontend tests (single account, multiple accounts, no mapping, admin redirect, navigation)
- **Coverage Target**: ≥80% (backend service + controller, frontend components)

### III. API-First ✅ PASS
- **RESTful Endpoint**: GET /api/account-vulns (returns JSON with account groups, asset lists, vulnerability counts)
- **Backward Compatibility**: New endpoint, no changes to existing APIs
- **Consistent Error Format**: Standard error responses for no mappings (404), admin access (403), unauthorized (401)
- **HTTP Status Codes**: 200 (success), 401 (unauthorized), 403 (admin forbidden), 404 (no mappings)

### IV. Docker-First ✅ PASS
- **No Infrastructure Changes**: Existing Dockerfiles/docker-compose.yml sufficient
- **Environment Config**: No new environment variables required (uses existing DB connection)

### V. Role-Based Access Control (RBAC) ✅ PASS
- **Endpoint Security**: @Secured(SecurityRule.IS_AUTHENTICATED) on /api/account-vulns
- **Role Checks**: Admin role check to trigger redirect message
- **Clarification Applied**: AWS account mapping is primary access control (workgroup restrictions explicitly DO NOT apply per clarification Q1)
- **UI Role Checks**: Frontend checks authentication + admin role for menu styling and redirect display

### VI. Schema Evolution ✅ PASS
- **No Schema Changes**: Uses existing tables (user_mapping, assets, vulnerabilities, users)
- **Existing Indexes**: Leverages existing indexes on user_mapping.email, assets.cloudAccountId, vulnerabilities.asset_id

**Overall Status**: ✅ ALL GATES PASS - Ready for Phase 0 research

## Project Structure

### Documentation (this feature)

```
specs/018-under-vuln-management/
├── spec.md              # Feature specification (completed)
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (pending)
├── data-model.md        # Phase 1 output (pending)
├── quickstart.md        # Phase 1 output (pending)
├── contracts/           # Phase 1 output (pending)
│   └── account-vulns-api.yaml
├── checklists/          # Quality validation (existing)
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
# Web application structure (backend + frontend)
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── controller/
│   │   └── AccountVulnsController.kt        # NEW: GET /api/account-vulns endpoint
│   ├── service/
│   │   └── AccountVulnsService.kt           # NEW: Business logic for account-based filtering
│   ├── dto/
│   │   ├── AccountVulnsSummaryDto.kt        # NEW: Response DTO (account groups)
│   │   └── AssetVulnCountDto.kt             # NEW: Asset + vuln count DTO
│   └── domain/
│       ├── UserMapping.kt                   # EXISTING: Used for AWS account lookup
│       ├── Asset.kt                         # EXISTING: cloudAccountId field used
│       ├── Vulnerability.kt                 # EXISTING: Count per asset
│       └── User.kt                          # EXISTING: Email + role checks
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── AccountVulnsContractTest.kt      # NEW: Contract tests for API
    ├── service/
    │   └── AccountVulnsServiceTest.kt       # NEW: Unit tests for business logic
    └── integration/
        └── AccountVulnsIntegrationTest.kt   # NEW: Full flow tests

src/frontend/
├── src/
│   ├── pages/
│   │   └── account-vulns.astro              # NEW: Main page route
│   ├── components/
│   │   ├── AccountVulnsView.tsx             # NEW: Main view component
│   │   ├── AccountVulnGroup.tsx             # NEW: Per-account group component
│   │   └── AssetVulnTable.tsx               # NEW: Asset list table
│   ├── services/
│   │   └── accountVulnsService.ts           # NEW: API client for /api/account-vulns
│   └── layouts/
│       └── MainLayout.astro                 # MODIFIED: Add Account Vulns nav item
└── tests/e2e/
    └── account-vulns.spec.ts                # NEW: Playwright E2E tests
```

**Structure Decision**: Standard web application structure with separate backend (Kotlin/Micronaut) and frontend (Astro/React) directories. This feature adds new files to existing structure - no structural changes required. Backend follows domain-driven design (controller → service → repository layers). Frontend follows Astro pages + React islands pattern.

## Complexity Tracking

*No constitutional violations - this section is empty.*

---

## Phase 1 Complete: Constitution Re-Check

*Re-evaluation after design artifacts generated*

### I. Security-First ✅ PASS (Re-confirmed)
- **API Contract**: OpenAPI spec documents authentication requirement (bearerAuth), status codes (401/403/404)
- **Error Responses**: Separate 403 (admin) vs 404 (no mapping) provides clear security semantics
- **No Sensitive Data**: Vulnerability counts only (no CVE details, severity in list view)

### II. Test-Driven Development ✅ PASS (Re-confirmed)
- **Contract Tests Specified**: 10 test scenarios documented in data-model.md (Backend Contract Tests section)
- **Unit Tests Specified**: Service layer mocking strategy documented in data-model.md
- **E2E Tests Specified**: 8 Playwright test scenarios documented in data-model.md
- **Quickstart Guide**: Includes TDD workflow (Red-Green-Refactor) with test templates

### III. API-First ✅ PASS (Re-confirmed)
- **OpenAPI 3.0 Contract**: Complete specification in contracts/account-vulns-api.yaml
- **Examples Provided**: Single account, multiple accounts, error cases all documented
- **RESTful Design**: GET /api/account-vulns (resource-oriented, standard HTTP methods)
- **Versioning**: OpenAPI version 1.0.0, no breaking changes to existing APIs

### IV. Docker-First ✅ PASS (Re-confirmed)
- **Quickstart Setup**: Docker Compose command documented (`docker-compose up -d mariadb`)
- **No New Containers**: Existing infrastructure sufficient (MariaDB, backend, frontend)

### V. RBAC ✅ PASS (Re-confirmed)
- **API Security**: OpenAPI spec documents `bearerAuth` security scheme (JWT)
- **Admin Handling**: 403 response with redirect guidance (System Vulns)
- **Frontend Role Checks**: Navigation menu styling based on admin role (quickstart documented)

### VI. Schema Evolution ✅ PASS (Re-confirmed)
- **No Schema Changes**: data-model.md confirms use of existing tables only
- **Index Usage**: Documented existing indexes used (email, cloudAccountId, asset_id)
- **Migration**: N/A (no schema changes)

**Phase 1 Status**: ✅ ALL GATES PASS - Ready for Phase 2 (Task Generation via `/speckit.tasks`)

---

## Artifacts Generated (Phase 0 & 1)

### Phase 0: Research
- ✅ `research.md` - 6 technical decisions documented, alternatives considered, best practices applied

### Phase 1: Design & Contracts
- ✅ `data-model.md` - Existing entities + 3 new DTOs documented, query strategies defined
- ✅ `contracts/account-vulns-api.yaml` - OpenAPI 3.0 specification (GET /api/account-vulns)
- ✅ `quickstart.md` - Development setup, testing workflow, debugging guide
- ✅ `CLAUDE.md` - Agent context updated with new tech stack details

---

## Next Command

Run `/speckit.tasks` to generate task decomposition (`tasks.md`) for implementation.

**Note**: `/speckit.plan` command ends here. Task generation is a separate phase (Phase 2).
