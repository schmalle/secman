# Implementation Plan: Future User Mapping Support

**Branch**: `042-future-user-mappings` | **Date**: 2025-11-07 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/042-future-user-mappings/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Extend the existing UserMapping upload functionality to support mappings for users who don't yet exist in the system. When future users are created (manually or via OAuth auto-provisioning), their pre-configured mappings (AWS account ID, domain) will be automatically applied, granting immediate asset access according to the unified access control rules. The implementation will retain applied mappings as historical records, use email uniqueness constraints, and provide separate UI tabs for current vs applied mappings.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), JavaScript ES2022 (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, MariaDB 12, Apache POI 5.3, Apache Commons CSV, Astro 5.14, React 19, Bootstrap 5.3, Axios
**Storage**: MariaDB 12 (existing `user_mapping` table - schema extension required to support nullable user_id and add appliedAt timestamp)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (backend), modern browsers (frontend)
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)
**Performance Goals**: <2 seconds for mapping application during user creation (NFR-001), support 10,000+ future user mappings without degradation (NFR-002)
**Constraints**: <2 seconds mapping application latency, <1KB per mapping storage (NFR-003), case-insensitive email matching
**Scale/Scope**: Extends existing Feature 013/016 (UserMapping), integrates with Feature 041 (OAuth auto-provisioning), affects admin users only (ADMIN role required for mapping management)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅

- **File uploads**: Existing Excel/CSV validation reused (size ≤10MB, format validation, content-type checks)
- **Input sanitization**: Email format validation, 12-digit AWS account ID validation, domain format validation (all existing rules from Feature 013/016)
- **RBAC**: ADMIN role required for mapping upload/management (existing @Secured annotations), applies to both future and active mappings
- **Sensitive data**: No sensitive data logged (minimal logging: timestamp + email only per FR-013)
- **Authentication**: JWT in sessionStorage (existing mechanism)

**Status**: PASS - Reuses existing security patterns, no new vulnerabilities introduced

### II. Test-Driven Development (NON-NEGOTIABLE) ✅

- **Contract tests**: Required for new API endpoints (if any additions beyond existing upload endpoints)
- **Integration tests**: Required for user creation flow with mapping application, upload flow for mixed future/active users
- **Unit tests**: Required for mapping lookup logic, conflict resolution, email matching (case-insensitive), appliedAt timestamp handling
- **Test coverage target**: ≥80%
- **Backend testing**: JUnit 5 + MockK
- **Frontend testing**: Playwright for E2E (tab switching, status display)

**Status**: PASS - Standard TDD approach, no exceptions needed

### III. API-First ✅

- **RESTful design**: Extends existing `/api/import/upload-user-mappings` and `/api/import/upload-user-mappings-csv` endpoints
- **Backward compatibility**: Existing endpoints must continue to work for current users, new behavior is additive (accepts future users without errors)
- **Error formats**: Consistent with existing ImportResult format
- **HTTP status codes**: Standard 200 (success), 400 (validation), 401 (auth), 403 (authorization)
- **OpenAPI documentation**: Update existing UserMapping schemas to include appliedAt field

**Status**: PASS - Extends existing APIs without breaking changes

### IV. User-Requested Testing ✅

- **Test planning**: Will only prepare detailed test cases if explicitly requested
- **Test tasks**: Will mark test tasks as OPTIONAL in tasks.md unless requested
- **TDD principle**: Tests will be written first when implementation begins (per Principle II)

**Status**: PASS - Tests written during implementation per TDD, not planned upfront

### V. Role-Based Access Control (RBAC) ✅

- **API endpoints**: @Secured("ADMIN") required for all mapping management operations (existing pattern)
- **Roles**: ADMIN role only (existing)
- **Frontend checks**: Role checks before rendering mapping management UI (existing)
- **Workgroup filtering**: N/A - user mappings are global admin function, not workgroup-scoped
- **Service layer**: Authorization checks in service layer (existing pattern)

**Status**: PASS - Reuses existing RBAC pattern for admin-only operations

### VI. Schema Evolution ✅

- **Migration approach**: Hibernate auto-migration (existing configuration)
- **Schema changes required**:
  - `user_mapping.user_id`: Change from NOT NULL to nullable (allows future user mappings without user reference)
  - `user_mapping.applied_at`: Add new TIMESTAMP column (nullable, default NULL)
  - `user_mapping.email`: Add UNIQUE constraint if not already present (per clarification Q1)
- **Foreign key relationships**: Existing user_id FK to users table remains, but must allow NULL
- **Indexes**: Add index on applied_at for "Applied History" query performance, maintain existing email index
- **Data loss**: No data loss - purely additive schema changes

**Status**: PASS - Standard schema evolution, no migrations issues expected

### Constitution Compliance Summary

✅ **ALL GATES PASSED** - Feature fully compliant with Secman constitution v2.0.0

No violations or exceptions required. This is an extension of existing functionality (Feature 013/016) using established patterns.

## Project Structure

### Documentation (this feature)

```text
specs/042-future-user-mappings/
├── spec.md              # Feature specification (complete)
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (created below)
├── data-model.md        # Phase 1 output (created below)
├── quickstart.md        # Phase 1 output (created below)
├── contracts/           # Phase 1 output (created below)
│   └── user-mapping-api.yaml  # OpenAPI schema updates
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
# Web application structure (backend + frontend)
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   └── UserMapping.kt              # MODIFY: Add appliedAt field, make user nullable
│   ├── repository/
│   │   └── UserMappingRepository.kt    # MODIFY: Add query methods for future users
│   ├── service/
│   │   ├── ImportService.kt            # MODIFY: Handle future user mappings in upload
│   │   ├── UserService.kt              # MODIFY: Apply mappings on user creation
│   │   └── UserMappingService.kt       # NEW: Service for future user mapping logic
│   └── controller/
│       └── ImportController.kt         # MODIFY: Update import endpoints if needed
└── src/test/kotlin/com/secman/
    ├── service/
    │   ├── ImportServiceTest.kt        # MODIFY: Add future user mapping tests
    │   ├── UserServiceTest.kt          # MODIFY: Add mapping application tests
    │   └── UserMappingServiceTest.kt   # NEW: Future user mapping unit tests
    └── integration/
        └── UserMappingIntegrationTest.kt  # NEW: End-to-end mapping application tests

src/frontend/
├── src/
│   ├── components/
│   │   └── UserMappingManagement.tsx   # MODIFY: Add tabs for Current/Applied History
│   ├── pages/
│   │   └── admin/
│   │       └── user-mappings.astro     # MODIFY: Update page if needed
│   └── services/
│       └── userMappingService.ts       # MODIFY: Add API calls for tabs
└── tests/
    └── e2e/
        └── user-mappings.spec.ts       # MODIFY: Add E2E tests for tabs and future user flow
```

**Structure Decision**: Web application structure (Option 2 from template). This feature extends existing backend entities and frontend components. Primary changes are in `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt` (entity), `UserService.kt` (user creation hook), and `src/frontend/src/components/UserMappingManagement.tsx` (UI tabs).

## Complexity Tracking

No violations or exceptions required - all constitution checks passed.

---

## Phase 1 Complete: Post-Design Constitution Re-Check

*Executed after completing Phase 1 (Design & Contracts)*

### Design Artifacts Created

1. ✅ **research.md**: Technical decisions documented (nullable FK pattern, event listeners, case-insensitive matching, conflict resolution, UI tabs, performance optimization, audit logging)
2. ✅ **data-model.md**: Entity changes documented (UserMapping entity extension, repository methods, migration strategy, query patterns)
3. ✅ **contracts/user-mapping-api.yaml**: OpenAPI specification for API extensions
4. ✅ **quickstart.md**: Developer onboarding guide with implementation checklist and code snippets
5. ✅ **Agent context updated**: CLAUDE.md updated with new technologies

### Constitution Re-Validation

#### I. Security-First ✅

**Design Review**:
- ✅ Nullable FK pattern preserves referential integrity when user exists
- ✅ Email validation reuses existing patterns (format, AWS account ID, domain)
- ✅ RBAC enforced via @Secured("ADMIN") on all endpoints
- ✅ Minimal logging prevents sensitive data exposure
- ✅ Case-insensitive email matching prevents security bypass via casing

**Status**: PASS - Design maintains security-first principles

#### II. Test-Driven Development ✅

**Design Review**:
- ✅ Quickstart.md includes comprehensive testing checklist
- ✅ Unit test requirements defined (service layer, email matching, conflict resolution)
- ✅ Integration test requirements defined (user creation flow, mixed uploads)
- ✅ E2E test requirements defined (tab switching, mapping application)
- ✅ TDD approach documented: tests written first, then implementation

**Status**: PASS - Testing strategy comprehensive and TDD-compliant

#### III. API-First ✅

**Design Review**:
- ✅ OpenAPI specification complete with schemas and endpoints
- ✅ Backward compatibility maintained (existing endpoints unchanged behavior)
- ✅ New endpoints follow RESTful patterns (GET for lists, DELETE for removal)
- ✅ Consistent error response format (Error schema)
- ✅ Pagination supported for scalability

**Status**: PASS - API design follows REST principles and maintains compatibility

#### IV. User-Requested Testing ✅

**Design Review**:
- ✅ Test planning deferred until implementation phase (per principle)
- ✅ Test framework requirements documented but specific test cases not pre-written
- ✅ TDD principle still enforced (tests written first during implementation)

**Status**: PASS - Respects user autonomy on test planning

#### V. Role-Based Access Control ✅

**Design Review**:
- ✅ All endpoints secured with bearerAuth (JWT)
- ✅ ADMIN role required for all mapping operations (documented in OpenAPI)
- ✅ No workgroup-based filtering needed (admin-only global function)
- ✅ Service layer authorization implicit (called from secured controllers)

**Status**: PASS - RBAC consistently applied across API surface

#### VI. Schema Evolution ✅

**Design Review**:
- ✅ Hibernate auto-migration strategy documented
- ✅ Schema changes are purely additive (no data loss)
- ✅ Indexes defined for performance (email unique, applied_at non-unique)
- ✅ Foreign key relationship maintained with nullable constraint
- ✅ Migration SQL documented in data-model.md

**Status**: PASS - Schema evolution follows safe migration practices

### Post-Design Constitution Summary

✅ **ALL GATES PASSED** - Design fully compliant with Secman constitution v2.0.0

No design changes required. Implementation can proceed according to plan.

---

## Implementation Readiness

**Status**: ✅ READY FOR IMPLEMENTATION (`/speckit.tasks`)

All planning phases complete:
- ✅ Phase 0: Research completed (research.md)
- ✅ Phase 1: Design completed (data-model.md, contracts/, quickstart.md)
- ✅ Constitution checks: All passed (pre-design and post-design)
- ✅ Agent context: Updated (CLAUDE.md)

**Next Command**: `/speckit.tasks` to generate actionable task list
