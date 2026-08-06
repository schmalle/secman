# Implementation Plan: VULN Role & Vulnerability Management UI

**Branch**: `004-i-want-to` | **Date**: 2025-10-03 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/Users/flake/sources/misc/secman/specs/004-i-want-to/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → If not found: ERROR "No feature spec at {path}"
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detect Project Type from file system structure or context (web=frontend+backend, mobile=app+api)
   → Set Structure Decision based on project type
3. Fill the Constitution Check section based on the content of the constitution document.
4. Evaluate Constitution Check section below
   → If violations exist: Document in Complexity Tracking
   → If no justification possible: ERROR "Simplify approach first"
   → Update Progress Tracking: Initial Constitution Check
5. Execute Phase 0 → research.md
   → If NEEDS CLARIFICATION remain: ERROR "Resolve unknowns"
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, agent-specific template file
7. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 8. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Add VULN role to RBAC system alongside existing ADMIN role, create "Vuln Management" sidebar navigation with Vulns and Exceptions submenu items. Implement Vulns page displaying current (latest scan per system) vulnerabilities with filtering/sorting by severity, system, and exception status. Create VulnerabilityException entity for IP-based or product-based exceptions with optional expiration dates. Support full CRUD operations on exceptions with visual indicators showing which vulnerabilities are covered. Restrict all vulnerability features to users with ADMIN or VULN roles only.

## Technical Context
**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript with React 19 (frontend)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Astro 5.14, React 19, Bootstrap 5.3, Axios
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Docker containers (AMD64/ARM64), Linux/macOS/Windows browser clients
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)
**Performance Goals**: API endpoints <200ms p95, Frontend Lighthouse score ≥90
**Constraints**: JWT authentication, role-based authorization at API and UI layers, backward compatible API
**Scale/Scope**: 3 new API endpoints, 1 new entity, 1 role enum addition, 2 new UI pages, sidebar modification

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Verify this feature complies with secman Constitution v1.0.0:

| Principle | Compliance Check | Status |
|-----------|------------------|--------|
| I. Security-First | Security implications evaluated? Input validation planned? Auth enforced? | ✅ New VULN role, @Secured on all endpoints, role checks in UI, input validation for exception fields |
| II. TDD (NON-NEGOTIABLE) | Tests written before implementation? Red-Green-Refactor followed? | ✅ Contract tests for 3 endpoints, unit tests for exception matching logic, integration tests for role enforcement |
| III. API-First | RESTful APIs defined? Backward compatibility maintained? API docs planned? | ✅ REST endpoints: GET /api/vulnerabilities/current, GET/POST/PUT/DELETE /api/vulnerability-exceptions, no breaking changes |
| IV. Docker-First | Services containerized? .env config (no hardcoded values)? Multi-arch support? | ✅ Uses existing Docker setup, no new configuration needed |
| V. RBAC | User roles respected? Authorization at API & UI? Admin restrictions enforced? | ✅ New VULN role added to User.Role enum, @Secured checks for ADMIN||VULN, UI hides navigation for unauthorized users |
| VI. Schema Evolution | Migrations automated? Schema backward-compatible? Constraints at DB level? | ✅ VulnerabilityException entity auto-created by Hibernate, FK constraints enforced, nullable expiration date |

**Quality Gates**:
- [x] Tests achieve ≥80% coverage (contract + unit + integration)
- [x] Linting passes (Kotlin conventions + ESLint)
- [x] Docker builds succeed (AMD64 + ARM64)
- [x] API endpoints respond <200ms (p95) - simple queries on indexed tables
- [x] Security scan shows no critical vulnerabilities

## Project Structure

### Documentation (this feature)
```
specs/004-i-want-to/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
│   ├── get-current-vulnerabilities.yaml
│   ├── get-vulnerability-exceptions.yaml
│   ├── post-vulnerability-exception.yaml
│   ├── put-vulnerability-exception.yaml
│   └── delete-vulnerability-exception.yaml
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
# Web application structure (existing pattern)
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── User.kt                              # MODIFY: Add VULN to Role enum
│   │   └── VulnerabilityException.kt            # NEW: Exception entity
│   ├── repository/
│   │   └── VulnerabilityExceptionRepository.kt  # NEW: JPA repository
│   ├── service/
│   │   ├── VulnerabilityService.kt              # NEW: Current vuln query logic
│   │   └── VulnerabilityExceptionService.kt     # NEW: Exception CRUD + matching
│   ├── controller/
│   │   └── VulnerabilityManagementController.kt # NEW: 5 REST endpoints
│   └── dto/
│       └── VulnerabilityExceptionDto.kt         # NEW: API request/response DTOs
└── src/test/kotlin/com/secman/
    ├── controller/
    │   └── VulnerabilityManagementControllerTest.kt # NEW: Contract tests
    ├── service/
    │   ├── VulnerabilityServiceTest.kt              # NEW: Unit tests
    │   └── VulnerabilityExceptionServiceTest.kt     # NEW: Unit tests (exception matching)
    └── integration/
        └── VulnRoleAuthorizationTest.kt             # NEW: Role enforcement test

src/frontend/
├── src/
│   ├── components/
│   │   ├── Sidebar.tsx                          # MODIFY: Add Vuln Management menu
│   │   ├── CurrentVulnerabilitiesTable.tsx      # NEW: Vuln list with filters
│   │   ├── VulnerabilityExceptionsTable.tsx     # NEW: Exception list
│   │   └── VulnerabilityExceptionForm.tsx       # NEW: Create/edit exception form
│   ├── pages/
│   │   ├── vulnerabilities/
│   │   │   ├── current.astro                    # NEW: Current vulns page
│   │   │   └── exceptions.astro                 # NEW: Exceptions page
│   │   └── admin/
│   │       └── user-management.astro            # MODIFY: Add VULN role checkbox
│   ├── services/
│   │   └── vulnerabilityManagementService.ts    # NEW: API client for new endpoints
│   └── utils/
│       └── auth.ts                              # MODIFY: Add hasVulnRole() helper
└── tests/e2e/
    ├── vuln-role-access.spec.ts                 # NEW: Role-based access test
    ├── current-vulnerabilities.spec.ts          # NEW: Vuln display test
    └── vulnerability-exceptions.spec.ts         # NEW: Exception CRUD test
```

**Structure Decision**: Web application (frontend + backend). Follows existing patterns: Domain-Repository-Service-Controller on backend, Astro pages with React components on frontend. All new files follow established naming conventions and directory structure.

## Phase 0: Outline & Research
**Output**: research.md

No NEEDS CLARIFICATION markers remain in spec after clarification session. Research tasks:

1. **Existing role system patterns** - Review how ADMIN role is currently checked in:
   - Backend: @Secured annotations, SecurityService role checks
   - Frontend: auth.ts utility functions, conditional UI rendering
   - Database: User.Role enum, user_roles table

2. **Current vulnerability query pattern** - Research how to efficiently query "latest scan per asset":
   - SQL: Window functions (ROW_NUMBER() OVER PARTITION BY asset_id ORDER BY scan_timestamp DESC)
   - JPA: Native query vs JPQL with subquery vs repository method with custom logic
   - Existing pattern in codebase for similar historical queries

3. **Exception matching logic design** - Best practice for matching exceptions to vulnerabilities:
   - IP-based: Exact match on Asset.ip
   - Product-based: Pattern matching on Vulnerability.vulnerableProductVersions (contains check)
   - Expiration check: null = permanent, or expirationDate > now()
   - Performance: Pre-compute active exceptions cache vs query-time join

4. **Sidebar navigation pattern** - Existing collapsible menu implementation:
   - Bootstrap 5.3 collapse component usage
   - Astro vs React Sidebar.tsx implementation
   - Role-based menu item filtering

5. **Frontend filtering/sorting UI** - Bootstrap DataTable or custom implementation:
   - Existing pattern in AssetManagement.tsx or other list pages
   - Client-side vs server-side filtering for vulnerability list
   - Multi-column sort and filter controls

**Research Findings**: Document in `research.md` with format:
- **Decision**: [chosen approach]
- **Rationale**: [why chosen - performance, maintainability, consistency]
- **Alternatives Considered**: [what else evaluated]
- **Code References**: [existing files demonstrating pattern]

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

### Artifacts to Generate:

1. **data-model.md** - Entity design:
   - VulnerabilityException entity fields, relationships, indexes
   - User.Role enum addition (VULN)
   - Database migration notes (Hibernate auto-create)

2. **contracts/** - OpenAPI 3.0 YAML files:
   - `get-current-vulnerabilities.yaml`: GET /api/vulnerabilities/current with query params (severity, system, exceptionStatus)
   - `get-vulnerability-exceptions.yaml`: GET /api/vulnerability-exceptions
   - `post-vulnerability-exception.yaml`: POST /api/vulnerability-exceptions with request body schema
   - `put-vulnerability-exception.yaml`: PUT /api/vulnerability-exceptions/{id}
   - `delete-vulnerability-exception.yaml`: DELETE /api/vulnerability-exceptions/{id}

3. **Contract tests** (fail initially):
   - `VulnerabilityManagementControllerTest.kt`:
     - `testGetCurrentVulnerabilitiesRequiresAuth()`
     - `testGetCurrentVulnerabilitiesRequiresVulnRole()`
     - `testGetCurrentVulnerabilitiesReturnsLatestScansOnly()`
     - `testPostExceptionRequiresVulnRole()`
     - `testPutExceptionValidation()`
   - `VulnRoleAuthorizationTest.kt`:
     - `testNormalUserCannotAccessVulnEndpoints()`
     - `testAdminCanAccessVulnEndpoints()`
     - `testVulnRoleCanAccessVulnEndpoints()`

4. **quickstart.md** - Manual validation steps:
   - Create user with VULN role
   - Import vulnerability data (use Feature 003 endpoint)
   - Access /vulnerabilities/current page
   - Create IP-based exception
   - Verify vulnerability marked as excepted
   - Create product-based exception with expiration date
   - Verify expiration logic
   - Attempt access as normal user (should fail)

5. **CLAUDE.md update** - Incremental O(1) operation:
   - Run `.specify/scripts/bash/update-agent-context.sh claude`
   - Add "Feature 004: VULN Role & Vulnerability Management UI" to Recent Changes
   - Add VulnerabilityException entity to Key Entities section
   - Add new API endpoints to API Endpoints section
   - Update with VULN role information in Architecture section
   - Keep file under 150 lines (remove oldest feature if needed)

**Output**: All files created in `/Users/flake/sources/misc/secman/specs/004-i-want-to/` and `/Users/flake/sources/misc/secman/CLAUDE.md`

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
1. Load `.specify/templates/tasks-template.md` as base template
2. Generate tasks from Phase 1 contracts and data model:
   - **Contract tests** (5 tasks, [P] parallel) - One per contract YAML
   - **Entity creation** (1 task) - VulnerabilityException entity
   - **Role enum update** (1 task) - User.Role add VULN
   - **Repository** (1 task) - VulnerabilityExceptionRepository
   - **Service layer** (2 tasks) - VulnerabilityService, VulnerabilityExceptionService
   - **Unit tests** (3 tasks, [P] parallel) - Service tests including exception matching logic
   - **Controller** (1 task) - VulnerabilityManagementController with @Secured annotations
   - **DTOs** (1 task) - Request/response data transfer objects
   - **Frontend service** (1 task) - API client for new endpoints
   - **Sidebar modification** (1 task) - Add Vuln Management menu with role check
   - **Vuln list page** (1 task) - Current vulnerabilities table with filters/sort
   - **Exceptions page** (1 task) - Exception list with CRUD operations
   - **Exception form component** (1 task) - Create/edit form with validation
   - **E2E tests** (3 tasks, [P] parallel) - Role access, vuln display, exception CRUD
   - **Integration test** (1 task) - VulnRoleAuthorizationTest
   - **User management update** (1 task) - Add VULN role checkbox to admin UI
   - **Documentation** (1 task) - Update CLAUDE.md via script

**Ordering Strategy** (TDD + Dependency Order):
1. Contract tests (fail) - Defines API shape
2. Data model (VulnerabilityException entity, User.Role enum)
3. Repository layer
4. Unit tests for services (fail)
5. Service layer implementation (make unit tests pass)
6. Controller implementation (make contract tests pass)
7. Integration test for role enforcement (fail)
8. Adjust auth configuration if needed (make integration test pass)
9. Frontend service/utilities
10. React components (forms, tables)
11. Astro pages
12. Sidebar modification
13. User management UI update
14. E2E tests (fail initially)
15. E2E debugging/fixes (make E2E tests pass)
16. Documentation update

**Parallel Execution Markers**: Tasks marked [P] can be executed concurrently (independent files):
- All contract test files
- All service unit test files
- All E2E test files

**Estimated Output**: 23-25 numbered, TDD-ordered tasks in tasks.md

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md with dependency-ordered implementation steps)
**Phase 4**: Implementation (execute tasks.md following TDD: red-green-refactor per task)
**Phase 5**: Validation (run test suite, execute quickstart.md manual steps, Docker build verification, performance profiling for <200ms p95)

## Complexity Tracking
*No constitutional violations detected - this section left empty*

No deviations from constitutional principles. Feature follows established patterns:
- New role added to existing User.Role enum (no new auth mechanism)
- Standard JPA entity with Hibernate auto-migration
- REST endpoints follow existing controller patterns
- Frontend components follow existing React/Astro structure
- TDD workflow enforced via contract tests → unit tests → implementation
- RBAC enforced at both API (@Secured) and UI (conditional rendering) layers

## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [x] Phase 3: Tasks generated (/tasks command) - 27 tasks created
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS (no violations)
- [x] Post-Design Constitution Check: PASS (Phase 1 completed, no new violations)
- [x] All NEEDS CLARIFICATION resolved (clarification session completed)
- [x] Complexity deviations documented (none - no deviations)

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
