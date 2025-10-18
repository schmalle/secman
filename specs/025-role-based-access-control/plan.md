# Implementation Plan: Role-Based Access Control - RISK, REQ, and SECCHAMPION Roles

**Branch**: `025-role-based-access-control` | **Date**: 2025-10-18 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/025-role-based-access-control/spec.md`

## Summary

Add three new user roles (RISK, REQ, SECCHAMPION) to the existing RBAC system with granular access control for Risk Management, Requirements, and Vulnerabilities sections. The implementation extends the existing User.Role enum, updates backend @Secured annotations, modifies frontend navigation visibility, and implements per-request role checking with access denial audit logging.

**Key Technical Approach**:
- Extend existing `User.Role` enum (already has CHAMPION and REQ, need to add RISK and rename CHAMPION → SECCHAMPION)
- Update @Secured annotations on backend controllers for Risk, Requirements, and Vulnerabilities endpoints
- Implement role-based navigation filtering in frontend Sidebar component
- Add access denial logging with structured context (user ID, roles, resource, timestamp)
- Update README.md with comprehensive role permission matrix

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Apache POI 5.3, Astro, React 19, Bootstrap 5.3
**Storage**: MariaDB 11.4 (User entity with roles as ElementCollection)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (Docker Compose multi-arch: AMD64/ARM64)
**Project Type**: web (Kotlin/Micronaut backend + Astro/React frontend)
**Performance Goals**: Role checks on every API request with <50ms overhead, role changes effective on next request (no caching)
**Constraints**: Per-request role validation (no session-level caching), generic error messages (no role requirement disclosure), 100% access denial logging
**Scale/Scope**: Extend existing 7 roles to include 3 new roles, update ~10-15 controllers, modify 1 navigation component, comprehensive README documentation

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅

**Compliance**:
- ✅ RBAC enforcement: @Secured annotations required on all endpoints (FR-008)
- ✅ Access denial logging: All denials logged with context (FR-014)
- ✅ Generic error messages: No role requirement disclosure (FR-013)
- ✅ Per-request role checking: No caching vulnerabilities (FR-008, Clarification #3)

**Implementation Requirements**:
- Add @Secured annotations with new roles to Risk Management, Requirements, Vulnerabilities controllers
- Implement structured access denial logging with SLF4J
- Return generic 403 responses with user-friendly messages
- Ensure role checks occur on every request (no session-level caching)

### II. Test-Driven Development (NON-NEGOTIABLE) ✅

**Compliance**:
- ✅ Contract tests first: Role authorization tests for each protected endpoint
- ✅ Integration tests: Multi-role permission combinations
- ✅ Unit tests: Navigation component role filtering logic
- ✅ E2E tests: Full user journeys for each role (Playwright)

**Test Strategy**:
1. Backend contract tests: Verify @Secured annotations enforce correct roles
2. Backend integration tests: Test role assignment, removal, multi-role scenarios
3. Frontend component tests: Navigation visibility based on roles
4. E2E tests: Complete user flows for RISK, REQ, SECCHAMPION roles

### III. API-First ✅

**Compliance**:
- ✅ RESTful design: Existing endpoints, updated @Secured annotations only
- ✅ Consistent error format: 403 Forbidden with standard error body
- ✅ Backward compatibility: Existing roles (USER, ADMIN, VULN, RELEASE_MANAGER) unchanged (FR-010)

**No API Changes**: This feature only modifies authorization rules on existing endpoints.

### IV. Docker-First ✅

**Compliance**:
- ✅ No container changes required
- ✅ Environment config: No new .env variables needed
- ✅ Multi-arch support: Maintained (no architecture-specific code)

### V. Role-Based Access Control (RBAC) ✅

**Compliance**:
- ✅ @Secured annotations: Update all Risk, Requirements, Vulnerabilities endpoints
- ✅ New roles: RISK, REQ, SECCHAMPION (rename existing CHAMPION)
- ✅ Frontend role checks: Navigation component conditional rendering
- ✅ Per-request validation: No session caching (FR-008)

**Role Permission Matrix** (to be implemented):

| Area                | ADMIN | RISK | REQ | SECCHAMPION | VULN | RELEASE_MGR | USER |
|---------------------|-------|------|-----|-------------|------|-------------|------|
| Risk Management     | ✅    | ✅   | ❌  | ✅          | ❌   | ❌          | ❌   |
| Requirements        | ✅    | ❌   | ✅  | ✅          | ❌   | ❌          | ❌   |
| Vulnerabilities     | ✅    | ❌   | ❌  | ✅          | ✅   | ❌          | ❌   |
| Admin Area          | ✅    | ❌   | ❌  | ❌          | ❌   | ❌          | ❌   |
| Workgroups          | ✅    | ❌   | ❌  | ❌          | ❌   | ❌          | ❌   |
| Releases            | ✅    | ❌   | ❌  | ❌          | ❌   | ✅          | ❌   |

### VI. Schema Evolution ✅

**Compliance**:
- ✅ Hibernate auto-migration: User.Role enum updated (CHAMPION → SECCHAMPION, add RISK)
- ✅ No breaking changes: Enum values are strings, existing data compatible
- ✅ Database migration: Automatic via ddl-auto

**Migration Strategy**:
- Existing `CHAMPION` role will be renamed to `SECCHAMPION` (requires data migration script)
- New `RISK` role added to enum
- `REQ` role already exists (no change needed)

**Re-evaluation after Phase 1**: ✅ All gates remain compliant post-design

## Project Structure

### Documentation (this feature)

```
specs/025-role-based-access-control/
├── plan.md              # This file (/speckit.plan output)
├── spec.md              # Feature specification (already exists)
├── research.md          # Phase 0 output (generated below)
├── data-model.md        # Phase 1 output (generated below)
├── quickstart.md        # Phase 1 output (generated below)
├── contracts/           # Phase 1 output (generated below)
│   ├── role-permission-matrix.md
│   └── access-denial-logging.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
# Web application structure (backend + frontend)

src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   └── User.kt                    # MODIFY: Update Role enum (CHAMPION→SECCHAMPION, add RISK)
│   ├── controller/
│   │   ├── RiskAssessmentController.kt    # MODIFY: Update @Secured annotations
│   │   ├── RequirementController.kt       # MODIFY: Update @Secured annotations
│   │   ├── VulnerabilityController.kt     # MODIFY: Update @Secured annotations
│   │   └── UserController.kt              # MODIFY: Add role validation
│   ├── service/
│   │   └── AccessDenialLogger.kt          # NEW: Structured logging for access denials
│   └── security/
│       └── RoleSecurityInterceptor.kt     # NEW: Per-request role validation interceptor
└── src/test/kotlin/com/secman/
    ├── contract/
    │   ├── RoleAuthorizationContractTest.kt   # NEW: Test @Secured enforcement
    │   └── AccessDenialLoggingContractTest.kt # NEW: Test audit logging
    └── integration/
        └── MultiRolePermissionTest.kt         # NEW: Test role combinations

src/frontend/
├── src/
│   ├── components/
│   │   ├── Sidebar.tsx                # MODIFY: Add role-based navigation filtering
│   │   └── PermissionDenied.tsx       # NEW: Generic permission error component
│   └── services/
│       └── authService.ts             # MODIFY: Add role helper functions
└── tests/e2e/
    ├── risk-role-access.spec.ts       # NEW: E2E test for RISK role
    ├── req-role-access.spec.ts        # NEW: E2E test for REQ role
    └── secchampion-role-access.spec.ts # NEW: E2E test for SECCHAMPION role

# Documentation
README.md                              # MODIFY: Add role permission matrix and descriptions
```

**Structure Decision**: Web application with Kotlin/Micronaut backend and Astro/React frontend. Backend handles RBAC enforcement via @Secured annotations and per-request interceptors. Frontend provides role-based UI rendering. Follows existing project structure from CLAUDE.md.

## Complexity Tracking

*No constitutional violations - all requirements align with existing principles.*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| N/A       | N/A        | N/A                                 |

---

## Phase 0: Research & Decision Points

See [research.md](./research.md) for detailed research findings.

**Key Decisions**:

1. **Role Enum Migration Strategy**:
   - Decision: Add migration script to rename CHAMPION → SECCHAMPION in database
   - Rationale: Existing CHAMPION role detected in User.kt, need backward-compatible rename
   - Alternative: Create new SECCHAMPION and deprecate CHAMPION (rejected: creates duplicate permissions)

2. **Access Denial Logging Implementation**:
   - Decision: SLF4J with structured logging (MDC context) + dedicated logger
   - Rationale: Consistent with existing Micronaut logging, easy to aggregate in log management systems
   - Alternative: Dedicated audit table (rejected: overkill for read-only audit trail, log aggregation sufficient)

3. **Per-Request Role Validation**:
   - Decision: Micronaut SecurityInterceptor with no caching
   - Rationale: Built-in framework support, enforces per-request checks without manual implementation
   - Alternative: Custom filter/middleware (rejected: reinvents framework capabilities)

4. **Frontend Role Checking**:
   - Decision: React context + role helper functions
   - Rationale: Centralized role state, reusable across components
   - Alternative: Prop drilling (rejected: poor maintainability)

5. **Navigation Visibility Logic**:
   - Decision: Conditional rendering in Sidebar.tsx based on role set
   - Rationale: Single source of truth for navigation, easy to test
   - Alternative: Separate navigation config file (rejected: adds indirection for simple use case)

---

## Phase 1: Design Artifacts

### Data Model

See [data-model.md](./data-model.md) for complete entity definitions.

**Summary**:
- **User Entity**: Update `Role` enum (add RISK, rename CHAMPION → SECCHAMPION)
- **Permission Mapping**: Define role-to-resource matrix (implemented via @Secured annotations)
- **Access Denial Log**: Structured log entries (SLF4J MDC with user_id, roles, resource, timestamp)

### API Contracts

See [contracts/](./contracts/) for detailed specifications.

**Summary**:
- **No New Endpoints**: Only authorization rule changes on existing endpoints
- **Updated @Secured Annotations**:
  - Risk Management: `@Secured(["ADMIN", "RISK", "SECCHAMPION"])`
  - Requirements: `@Secured(["ADMIN", "REQ", "SECCHAMPION"])`
  - Vulnerabilities: `@Secured(["ADMIN", "VULN", "SECCHAMPION"])`

### Quickstart

See [quickstart.md](./quickstart.md) for implementation guide.

**Key Steps**:
1. Update User.Role enum
2. Add access denial logging service
3. Update @Secured annotations on controllers
4. Modify frontend navigation component
5. Write tests (contract, integration, E2E)
6. Update README.md

---

## Next Steps

**Ready for**: `/speckit.tasks` (generate detailed implementation tasks)

**Artifacts Generated**:
- ✅ plan.md (this file)
- ✅ research.md
- ✅ data-model.md
- ✅ contracts/
- ✅ quickstart.md

**Post-Planning**:
1. Run `/speckit.tasks` to generate tasks.md with TDD-ordered implementation steps
2. Begin implementation following Red-Green-Refactor cycle
3. Ensure all tests pass before moving to next task
