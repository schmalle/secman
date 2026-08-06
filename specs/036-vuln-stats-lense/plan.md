# Implementation Plan: Vulnerability Statistics Lense

**Branch**: `036-vuln-stats-lense` | **Date**: 2025-10-28 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/036-vuln-stats-lense/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

This feature implements a vulnerability statistics dashboard ("Lense") as a sub-item under Vulnerability Management. It provides security teams with aggregated analytics including: most common vulnerabilities (top 10 by occurrence count), severity distribution (pie chart with percentages), asset vulnerability rankings (top 10 assets), vulnerability counts by asset type, and temporal trends (line chart over 30/60/90 days). Statistics are calculated dynamically on page load, respect workgroup access controls, and include interactive drill-down capabilities for detailed exploration.

## Technical Context

**Language/Version**: Backend: Kotlin 2.2.21 / Java 21; Frontend: Astro 5.14 + React 19
**Primary Dependencies**: Backend: Micronaut 4.10, Hibernate JPA; Frontend: React 19, Chart.js (or Recharts) for visualizations, Axios
**Storage**: MariaDB 11.4 (existing Vulnerability and Asset tables; no new tables required)
**Testing**: Backend: JUnit 5 + MockK; Frontend: Playwright E2E
**Target Platform**: Web application (server-side backend + client-side SPA)
**Project Type**: web (frontend + backend)
**Performance Goals**: <3s page load for datasets up to 10,000 vulnerabilities; statistical aggregation queries <1s
**Constraints**: Must respect workgroup access controls (non-ADMIN users only see authorized assets); no caching (always fresh calculation); interactive charts with drill-down navigation
**Scale/Scope**: Support 10,000+ vulnerabilities, 1,000+ assets, 90 days of historical trend data

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅

- **File Uploads**: N/A - No file uploads in this feature
- **Input Sanitization**: Query parameters for filtering/pagination must be validated (time ranges, severity filters, search terms)
- **RBAC Enforcement**:
  - API endpoints: `@Secured(SecurityRule.IS_AUTHENTICATED)` required on all statistics endpoints
  - Service layer: Workgroup filtering must be applied for non-ADMIN users
  - Frontend: UI checks for ADMIN/VULN roles to access Vulnerability Management section
- **Sensitive Data**: No sensitive data exposure risk; statistics are aggregated/anonymized
- **Authentication**: JWT from sessionStorage used for API calls (existing pattern)

**Status**: PASS - Standard security controls apply; no new attack vectors introduced

### II. Test-Driven Development ✅

- **Contract Tests**: Required for all new statistics API endpoints (most common vulns, severity distribution, asset rankings, temporal trends)
- **Unit Tests**: Required for aggregation logic, workgroup filtering, date range calculations
- **Integration Tests**: Required for database query performance with large datasets (10k+ vulnerabilities)
- **Coverage Target**: ≥80% for new code
- **Test Frameworks**: JUnit 5 + MockK (backend), Playwright (frontend E2E)

**Status**: PASS - TDD mandatory per constitution; tests will be written first per Principle II

**Note**: Per Principle IV (User-Requested Testing), test planning/preparation only occurs if user explicitly requests it. Tests will be written following TDD when implementation begins.

### III. API-First ✅

- **RESTful Design**:
  - `GET /api/vulnerability-statistics/most-common` - Top vulnerabilities by occurrence
  - `GET /api/vulnerability-statistics/severity-distribution` - Severity breakdown
  - `GET /api/vulnerability-statistics/top-assets` - Assets ranked by vuln count
  - `GET /api/vulnerability-statistics/by-asset-type` - Grouped by asset type
  - `GET /api/vulnerability-statistics/temporal-trends` - Time-series data with date range param
- **HTTP Status Codes**: 200 (success), 401 (unauthorized), 403 (forbidden - workgroup access), 500 (server error)
- **Error Format**: Consistent with existing API error responses
- **Backward Compatibility**: New endpoints only; no breaking changes to existing APIs
- **Documentation**: OpenAPI/Swagger docs will be updated

**Status**: PASS - New endpoints follow existing RESTful patterns; no breaking changes

### IV. User-Requested Testing ✅

- **Test Planning**: Test cases, test plans, and test tasks will ONLY be included if user explicitly requests testing
- **Test Marking**: If tests are requested, they will be clearly marked as OPTIONAL in tasks.md
- **TDD Compliance**: When tests ARE written (per user request), they will follow TDD principles (written first, fail before implementation)

**Status**: PASS - Planning workflow will not proactively prepare tests unless requested

### V. Role-Based Access Control ✅

- **Endpoint Security**: All `/api/vulnerability-statistics/*` endpoints require authentication (`@Secured(SecurityRule.IS_AUTHENTICATED)`)
- **Roles**: ADMIN and VULN roles have access to Vulnerability Management section
- **Workgroup Filtering**:
  - ADMIN users: See all vulnerabilities/assets
  - VULN users: Only see vulnerabilities on assets in their assigned workgroups
  - Filtering applied at service layer via JOIN on workgroup assignments
- **Frontend Checks**: Sidebar navigation only shows "Lense" link if user has ADMIN or VULN role
- **Authorization Layer**: Service methods will check roles and apply workgroup filters before query execution

**Status**: PASS - Consistent with existing RBAC patterns (Feature 008, 034)

### VI. Schema Evolution ✅

- **Database Changes**: NO new tables required
- **Entity Changes**: NO changes to existing entities (Vulnerability, Asset, User, Workgroup)
- **Queries Only**: Feature uses read-only aggregation queries (COUNT, GROUP BY, SUM)
- **Indexes**: Existing indexes on `vulnerability.vulnerability_id`, `vulnerability.cvss_severity`, `vulnerability.scan_timestamp`, `asset.id` sufficient for performance
- **Migration**: N/A - No schema changes

**Status**: PASS - Read-only feature; no schema evolution required

### Overall Constitutional Compliance

**Result**: ✅ **PASS** - All six constitutional principles satisfied

- No violations requiring justification
- Complexity Tracking table remains empty
- Standard patterns apply throughout
- Safe to proceed to Phase 0 research

### Post-Design Re-evaluation (Phase 1 Complete)

**Re-evaluation Date**: 2025-10-28
**Status**: ✅ **PASS** - All constitutional principles remain satisfied after design phase

**Design Artifacts Reviewed**:
- ✅ `research.md` - Chart.js selection justified with security and performance analysis
- ✅ `data-model.md` - No new entities; uses existing tables with proper access control
- ✅ `contracts/vulnerability-statistics-api.yaml` - Complete OpenAPI spec with authentication/authorization
- ✅ `quickstart.md` - Implementation guide follows TDD and existing patterns

**Constitutional Compliance After Design**:
1. **Security-First**: Query parameter validation defined in contracts; input sanitization patterns documented
2. **TDD**: Contract tests fully specified in OpenAPI; test patterns documented in quickstart
3. **API-First**: OpenAPI 3.0.3 spec complete with all 5 endpoints, schemas, and error responses
4. **User-Requested Testing**: No test tasks created proactively; quickstart documents test patterns only
5. **RBAC**: Workgroup filtering pattern from Feature 034 applied; authentication required on all endpoints
6. **Schema Evolution**: Confirmed no database changes; existing indexes sufficient for performance

**New Risks Identified**: None
**Violations Requiring Justification**: None
**Ready for Phase 2 (Tasks Generation)**: Yes

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   └── (no new entities - uses existing Vulnerability, Asset, User, Workgroup)
│   ├── repository/
│   │   ├── VulnerabilityRepository.kt (extend with statistics queries)
│   │   └── AssetRepository.kt (extend with statistics queries)
│   ├── service/
│   │   └── VulnerabilityStatisticsService.kt (NEW - aggregation logic)
│   └── controller/
│       └── VulnerabilityStatisticsController.kt (NEW - 5 REST endpoints)
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── VulnerabilityStatisticsControllerTest.kt (NEW - contract tests)
    ├── service/
    │   └── VulnerabilityStatisticsServiceTest.kt (NEW - unit tests)
    └── integration/
        └── VulnerabilityStatisticsIntegrationTest.kt (NEW - performance tests)

src/frontend/
├── src/
│   ├── components/
│   │   ├── statistics/
│   │   │   ├── MostCommonVulnerabilities.tsx (NEW - top 10 table)
│   │   │   ├── SeverityDistributionChart.tsx (NEW - pie chart)
│   │   │   ├── TopAssetsByVulnerabilities.tsx (NEW - asset rankings)
│   │   │   ├── VulnerabilityByAssetType.tsx (NEW - grouped bar chart)
│   │   │   └── TemporalTrendsChart.tsx (NEW - line chart with time range selector)
│   │   └── layout/
│   │       └── Sidebar.tsx (MODIFY - add "Lense" sub-item under Vulnerability Management)
│   ├── pages/
│   │   └── vulnerability-statistics.astro (NEW - main Lense page)
│   └── services/
│       └── api/
│           └── vulnerabilityStatisticsApi.ts (NEW - Axios API client)
└── tests/
    └── e2e/
        └── vulnerability-statistics.spec.ts (NEW - Playwright E2E tests)
```

**Structure Decision**: Web application structure (Option 2) - Backend uses existing Micronaut/Kotlin layered architecture (controller → service → repository → domain). Frontend uses Astro with React islands for interactive charts. New service and controller classes added; existing repositories extended with custom queries. No new domain entities required.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |
