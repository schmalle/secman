# Implementation Plan: CrowdStrike System Vulnerability Lookup

**Branch**: `015-we-have-currently` | **Date**: 2025-10-11 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/015-we-have-currently/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Enable security analysts to query the CrowdStrike Falcon API in real-time for system vulnerabilities (last 40 days, OPEN status), display results in a familiar table UI with filtering/sorting capabilities, and optionally persist to the local database for historical tracking and exception management. This feature integrates CrowdStrike's live vulnerability data into the existing vulnerability management system without manual export/import workflows.

**Technical Approach**: Create a new backend REST endpoint that integrates with CrowdStrike Falcon API using existing credentials, returns vulnerability data in a standardized format, and provides a save operation to persist results to existing Vulnerability/Asset entities. Frontend adds a new page with search form and results table reusing CurrentVulnerabilitiesTable design patterns.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Apache POI 5.3, Astro, React 19, Bootstrap 5.3, HTTP client for CrowdStrike API
**Storage**: MariaDB 11.4 via Hibernate JPA (existing Vulnerability and Asset entities, no schema changes)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E), contract tests for new API endpoints
**Target Platform**: Docker containers (multi-arch: AMD64/ARM64)
**Project Type**: web (frontend + backend)
**Performance Goals**: API query results in < 10 seconds (per SC-001), error messages within 3 seconds (per SC-004)
**Constraints**: CrowdStrike API rate limits (standard third-party API throttling), 40-day lookback window (business requirement), OPEN status filter
**Scale/Scope**: Single system query, estimated 1-1000 vulnerabilities per query, authenticated users (ADMIN/VULN roles)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅

- **File uploads**: N/A - no file uploads in this feature
- **Input sanitization**: ✅ System name input must be validated and sanitized before API calls (FR-002)
- **RBAC enforcement**: ✅ Endpoints secured with @Secured annotations, same permissions as Vuln Management (FR-018)
- **Sensitive data**: ✅ CrowdStrike API credentials stored in environment variables (Assumption 1), error messages must not expose credentials (FR-014)
- **Authentication tokens**: ✅ Uses existing JWT sessionStorage pattern

**Status**: PASS

### II. Test-Driven Development ✅

- **Contract tests**: Required for new CrowdStrike query endpoint and save endpoint
- **Integration tests**: Required for CrowdStrike API integration (mock API responses)
- **Unit tests**: Required for business logic (vulnerability mapping, asset matching)
- **E2E tests**: Required for frontend search flow, filter/sort, save action (Playwright)
- **Coverage**: Target ≥80%

**Status**: PASS - TDD workflow mandatory per constitution

### III. API-First ✅

- **RESTful design**: New endpoints follow REST patterns (GET for query, POST for save)
- **OpenAPI docs**: Contract files to be generated in Phase 1
- **Backward compatibility**: New endpoints, no breaking changes to existing APIs
- **Error formats**: Consistent with existing error response patterns
- **HTTP status codes**: Appropriate codes for success/error scenarios (200, 400, 401, 500)

**Status**: PASS

### IV. Docker-First ✅

- **Containerization**: Uses existing Docker setup, no new containers needed
- **Multi-arch**: Existing AMD64/ARM64 support continues
- **Environment config**: CrowdStrike credentials via .env (per assumptions)
- **Health checks**: Existing health checks sufficient

**Status**: PASS

### V. Role-Based Access Control ✅

- **@Secured annotations**: Required on new endpoints (FR-018)
- **Roles**: ADMIN and VULN roles have access (same as existing Vuln Management)
- **Frontend checks**: UI must check roles before rendering CrowdStrike lookup page
- **Service layer**: Authorization checks in service methods
- **Data filtering**: Workgroup filtering not applicable (system query by name, not asset-based initially)

**Status**: PASS

### VI. Schema Evolution ✅

- **Hibernate auto-migration**: No schema changes needed - reuses existing Vulnerability and Asset entities
- **Database constraints**: Existing constraints sufficient (Vulnerability foreign key to Asset, indexes on asset_id, scan_timestamp)
- **Foreign keys**: Existing relationships used (Vulnerability.asset → Asset)
- **Indexes**: Existing indexes cover query patterns

**Status**: PASS

**Overall Gate Status**: ✅ **PASS** - All constitutional principles satisfied, proceed to Phase 0

## Project Structure

### Documentation (this feature)

```
specs/015-we-have-currently/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── crowdstrike-query.openapi.yaml
│   └── crowdstrike-save.openapi.yaml
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── controller/
│   │   └── CrowdStrikeController.kt       # New: REST endpoints for query & save
│   ├── service/
│   │   └── CrowdStrikeVulnerabilityService.kt  # New: CrowdStrike API integration
│   ├── domain/
│   │   ├── Vulnerability.kt               # Existing: reused for persistence
│   │   └── Asset.kt                       # Existing: reused for asset matching/creation
│   └── dto/
│       └── CrowdStrikeVulnerabilityDto.kt # New: API response DTOs
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── CrowdStrikeContractTest.kt     # New: contract tests for endpoints
    ├── integration/
    │   └── CrowdStrikeIntegrationTest.kt  # New: API integration tests
    └── unit/
        └── CrowdStrikeServiceTest.kt      # New: service unit tests

src/frontend/
├── src/
│   ├── components/
│   │   └── CrowdStrikeVulnerabilityLookup.tsx  # New: search + results table
│   ├── pages/
│   │   └── crowdstrike-lookup.astro       # New: page route
│   └── services/
│       └── crowdstrikeService.ts          # New: API client for CrowdStrike endpoints
└── tests/e2e/
    └── crowdstrike-lookup.spec.ts         # New: E2E tests for search/filter/save flow
```

**Structure Decision**: Web application structure (Option 2). This feature extends the existing Micronaut backend with new controller/service layers for CrowdStrike integration, and adds a new Astro page with React component in the frontend. No new projects or containers required - integrates seamlessly into existing web architecture.

## Complexity Tracking

*No constitutional violations - this section intentionally left empty.*

---

## Phase 1 Design Artifacts ✅

### Constitution Re-check (Post-Design)

All constitutional principles remain satisfied after Phase 1 design:

- ✅ **Security-First**: API contracts specify authentication (JWT), authorization (ADMIN/VULN roles), input validation, no credential exposure in errors
- ✅ **TDD**: Four-layer test strategy documented (contract, integration, unit, E2E), tests written before implementation
- ✅ **API-First**: OpenAPI contracts created for both endpoints, RESTful design, consistent error formats
- ✅ **Docker-First**: No new containers, credentials via .env, uses existing setup
- ✅ **RBAC**: @Secured annotations in contracts, role checks documented
- ✅ **Schema Evolution**: No schema changes, reuses existing entities

**Gate Status**: ✅ **PASS** - Ready for Phase 2 (Task Generation)

### Generated Artifacts

1. **Research** ([research.md](./research.md)):
   - CrowdStrike API integration approach (Micronaut HTTP client with direct REST calls)
   - Authentication strategy (OAuth2 client credentials with token caching)
   - Error handling patterns (three-tier strategy)
   - Rate limiting approach (exponential backoff for 429)
   - Data mapping strategy (CrowdStrike → Vulnerability entity)
   - Testing strategy (four-layer TDD approach)

2. **Data Model** ([data-model.md](./data-model.md)):
   - New DTOs: CrowdStrikeVulnerabilityDto, CrowdStrikeQueryRequest/Response, CrowdStrikeSaveRequest/Response
   - Existing entities: Vulnerability, Asset, VulnerabilityException (reused, no changes)
   - Data flow diagrams (query flow, save flow)
   - Asset matching algorithm
   - Validation rules

3. **API Contracts**:
   - [Query Endpoint](./contracts/crowdstrike-query.openapi.yaml): GET /api/crowdstrike/vulnerabilities
   - [Save Endpoint](./contracts/crowdstrike-save.openapi.yaml): POST /api/crowdstrike/vulnerabilities/save
   - Full OpenAPI 3.0.3 specs with request/response schemas, error codes, examples

4. **Quickstart Guide** ([quickstart.md](./quickstart.md)):
   - End-user instructions (search, filter, save workflow)
   - Developer setup (environment variables, API endpoints, curl examples)
   - TDD workflow (contract → integration → unit → E2E → implementation)
   - Architecture diagrams

5. **Agent Context Update**:
   - Updated CLAUDE.md with new technology stack
   - Added CrowdStrike API integration details
   - Preserved existing manual additions

### Next Steps

Run `/speckit.tasks` to generate the dependency-ordered task list for implementation.
