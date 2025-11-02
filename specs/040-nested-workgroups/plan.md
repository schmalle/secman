# Implementation Plan: Nested Workgroups

**Branch**: `040-nested-workgroups` | **Date**: 2025-11-02 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/040-nested-workgroups/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Enable hierarchical organization of workgroups by implementing self-referential parent-child relationships. Administrators can create nested workgroup structures up to 5 levels deep, with automatic validation to prevent circular references and enforce sibling name uniqueness. When parent workgroups are deleted, children are promoted to the grandparent level. The solution uses optimistic locking for concurrent modification safety and maintains backward compatibility with existing flat workgroup structures.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), JavaScript ES2022 (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, MariaDB 11.4 (backend); Astro 5.14, React 19, Bootstrap 5.3, Axios (frontend)
**Storage**: MariaDB 11.4 with self-referential foreign key on `workgroup` table (`parent_id` column)
**Testing**: JUnit 5 + MockK (backend contract/integration/unit), Playwright (frontend E2E)
**Target Platform**: Linux server (backend), Modern browsers (frontend - Chrome/Firefox/Safari/Edge)
**Project Type**: web (Micronaut backend + Astro/React frontend)
**Performance Goals**: 500ms for hierarchy operations (ancestor/descendant queries, circular reference validation); 3 seconds page load for 100 workgroups
**Constraints**: 5 level maximum depth; 500 total workgroups system capacity; sibling name uniqueness; optimistic locking for concurrent modifications
**Scale/Scope**: Support up to 500 total workgroups across 5 hierarchy levels with minimal database optimization

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅

- **File validation**: Not applicable (no file uploads in this feature)
- **Input sanitization**: Required for workgroup name/description fields (XSS prevention)
- **RBAC enforcement**: @Secured(ADMIN) required on all hierarchy management endpoints
- **Sensitive data**: No sensitive data involved
- **Authentication**: JWT-based auth already in place

**Status**: Pass - ADMIN-only access enforced, input validation required

### II. Test-Driven Development (NON-NEGOTIABLE) ✅

- **Contract tests**: Required for all new endpoints (create child, move, delete with children)
- **Integration tests**: Required for hierarchy queries, circular reference validation
- **Unit tests**: Required for business logic (depth calculation, promotion on delete)
- **Coverage target**: ≥80%
- **Test frameworks**: JUnit 5 + MockK (backend), Playwright (frontend)

**Status**: Pass - TDD workflow will be followed

### III. API-First ✅

- **RESTful design**: Hierarchy endpoints follow REST conventions
- **Backward compatibility**: Existing workgroup endpoints unchanged; new optional `parent_id` field
- **Error formats**: Standard HTTP status codes (400 for validation, 409 for conflicts, 404 for not found)
- **Documentation**: OpenAPI specs required for new endpoints

**Status**: Pass - Backward compatible extension to existing API

### IV. User-Requested Testing ✅

- **Test planning**: Tests will be written per TDD but planning deferred unless requested
- **Test tasks**: Will be marked optional in tasks.md unless user requests testing phase

**Status**: Pass - TDD framework required, test planning user-driven

### V. Role-Based Access Control (RBAC) ✅

- **@Secured annotations**: Required on all new endpoints
- **Roles**: ADMIN role required for hierarchy management (create child, move, delete)
- **Frontend checks**: UI buttons hidden for non-ADMIN users
- **Service layer**: Authorization checks in WorkgroupService methods
- **Workgroup filtering**: Users see only workgroups they're assigned to (unchanged from current behavior)

**Status**: Pass - ADMIN-only operations with service-layer enforcement

### VI. Schema Evolution ✅

- **Hibernate auto-migration**: Will add `parent_id` foreign key and `version` column to `workgroup` table
- **Database constraints**: Foreign key constraint (parent_id references workgroup.id), unique constraint on (parent_id, name) for sibling uniqueness
- **Indexes**: Index on `parent_id` for hierarchy queries, partial index for root-level workgroups (parent_id IS NULL)
- **Migration safety**: Non-destructive (adds nullable column); existing workgroups become root-level by default

**Status**: Pass - Automated migration with proper constraints

**Overall Gate Result**: ✅ PASS - No violations, proceed to Phase 0

## Project Structure

### Documentation (this feature)

```text
specs/040-nested-workgroups/
├── spec.md              # Feature specification
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (to be generated)
├── data-model.md        # Phase 1 output (to be generated)
├── quickstart.md        # Phase 1 output (to be generated)
├── contracts/           # Phase 1 output (to be generated)
│   └── api.yaml         # OpenAPI spec for hierarchy endpoints
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
# Web application structure (backend + frontend)

src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   └── Workgroup.kt                    # Add parent_id, version fields; depth calculation
│   ├── repository/
│   │   └── WorkgroupRepository.kt          # Add hierarchy query methods (ancestors, descendants)
│   ├── service/
│   │   ├── WorkgroupService.kt             # Add createChild, move, deleteWithPromotion methods
│   │   └── WorkgroupValidationService.kt   # NEW: Circular reference, depth, sibling uniqueness validation
│   └── controller/
│       └── WorkgroupController.kt          # Add endpoints: POST /{id}/children, PUT /{id}/parent, DELETE /{id}
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── WorkgroupControllerTest.kt      # Contract tests for new endpoints
    ├── service/
    │   ├── WorkgroupServiceTest.kt         # Unit tests for hierarchy logic
    │   └── WorkgroupValidationServiceTest.kt
    └── integration/
        └── WorkgroupHierarchyIntegrationTest.kt # Integration tests for recursive queries

src/frontend/
├── src/
│   ├── components/
│   │   ├── WorkgroupTree.tsx               # NEW: Hierarchical tree component with expand/collapse
│   │   ├── WorkgroupBreadcrumb.tsx         # NEW: Ancestor path navigation
│   │   └── CreateChildWorkgroupModal.tsx   # NEW: Modal for creating child workgroups
│   ├── pages/
│   │   └── workgroups.astro                # Update to use WorkgroupTree component
│   └── services/
│       └── workgroupApi.ts                 # Add createChild, move, deleteWithChildren methods
└── tests/
    └── workgroups-hierarchy.spec.ts        # Playwright E2E tests
```

**Structure Decision**: Web application (Option 2) - feature touches both backend (API/domain) and frontend (UI components) following existing project structure.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations - section intentionally left empty.

---

## Phase 0: Research (COMPLETE)

**Output**: `research.md`

**Key Decisions Documented**:
1. Self-referential database design using adjacency list model
2. Optimistic locking with JPA @Version for concurrent modifications
3. Circular reference prevention via service-layer validation
4. Sibling name uniqueness with composite database constraint
5. Child promotion deletion strategy
6. Recursive CTEs for hierarchy queries in MariaDB
7. React lazy-loaded tree for frontend rendering
8. On-the-fly depth validation
9. Minimal audit logging via SLF4J

**Status**: ✅ All technical unknowns resolved

---

## Phase 1: Design & Contracts (COMPLETE)

**Outputs**:
- `data-model.md` - Entity changes, database schema, validation service
- `contracts/api.yaml` - OpenAPI specification for 7 new endpoints
- `quickstart.md` - Developer implementation guide
- `CLAUDE.md` - Updated agent context

**Entity Changes**:
- Modified Workgroup entity: added `parent`, `children`, `version` fields
- New WorkgroupValidationService for hierarchy validation
- Extended WorkgroupRepository with 5 new hierarchy query methods

**API Endpoints** (7 new):
1. `POST /workgroups/{id}/children` - Create child workgroup
2. `GET /workgroups/{id}/children` - Get direct children
3. `PUT /workgroups/{id}/parent` - Move workgroup to new parent
4. `DELETE /workgroups/{id}` - Delete workgroup (promotes children)
5. `GET /workgroups/{id}/ancestors` - Get ancestor path for breadcrumbs
6. `GET /workgroups/{id}/descendants` - Get entire subtree
7. `GET /workgroups/root` - Get all root-level workgroups

**Database Schema**:
- Added `parent_id` foreign key column (nullable)
- Added `version` column for optimistic locking
- Created index on `parent_id`
- Created unique constraint on `(parent_id, name)` for sibling uniqueness

**Frontend Components** (4 new):
- WorkgroupTree.tsx - Lazy-loaded tree with expand/collapse
- WorkgroupBreadcrumb.tsx - Ancestor path navigation
- CreateChildWorkgroupModal.tsx - Child creation modal
- Updated workgroups.astro page for tree rendering

**Status**: ✅ Design complete, contracts defined

---

## Post-Design Constitution Re-Check

### I. Security-First ✅

**Design Review**:
- Input validation: WorkgroupValidationService enforces name length, format, depth limits
- RBAC: @Secured("ADMIN") on all hierarchy modification endpoints (create, move, delete)
- XSS prevention: Name/description fields sanitized (string validation, length limits)
- No sensitive data exposure: Workgroup hierarchy is non-sensitive organizational data

**Status**: ✅ PASS - Security requirements met in design

### II. Test-Driven Development (NON-NEGOTIABLE) ✅

**Test Coverage Designed**:
- Contract tests: 10+ test cases for new endpoints (see quickstart.md)
- Unit tests: WorkgroupValidationService (4 methods), Workgroup entity helpers (3 methods)
- Integration tests: Recursive CTE queries, hierarchy operations, optimistic locking
- E2E tests: Playwright tests for tree UI, breadcrumb, modal

**TDD Workflow Confirmed**:
- Tests written first (contract → unit → integration → E2E)
- Coverage target: ≥80% achievable with designed test suite

**Status**: ✅ PASS - Comprehensive test design in place

### III. API-First ✅

**Design Review**:
- RESTful design: POST for create, GET for read, PUT for update, DELETE for delete
- Backward compatibility: ✅ Existing workgroup endpoints unchanged, new `parent_id` field optional in responses
- Error formats: Standard HTTP status codes (400, 401, 403, 404, 409) with ErrorResponse schema
- OpenAPI spec: contracts/api.yaml defines all endpoints with examples

**Breaking Changes**: None - fully backward compatible

**Status**: ✅ PASS - API-first design principles followed

### IV. User-Requested Testing ✅

**Approach**:
- Test framework requirements met (JUnit 5, MockK, Playwright)
- Test task planning deferred to user request (per constitutional principle IV)
- TDD workflow will be followed during implementation

**Status**: ✅ PASS - Test planning user-driven as required

### V. Role-Based Access Control (RBAC) ✅

**Design Review**:
- Endpoint security: All hierarchy modification endpoints use @Secured("ADMIN")
- Service layer: WorkgroupService methods check user authorization
- Frontend: UI buttons/modals hidden for non-ADMIN users (role checks)
- Workgroup filtering: Users see only assigned workgroups (existing RBAC unchanged)

**Access Control Matrix**:
| Operation | ADMIN | USER | VULN | RELEASE_MANAGER |
|-----------|-------|------|------|-----------------|
| Create child | ✅ | ❌ | ❌ | ❌ |
| Move workgroup | ✅ | ❌ | ❌ | ❌ |
| Delete (promote) | ✅ | ❌ | ❌ | ❌ |
| View hierarchy | ✅ | ✅ | ✅ | ✅ |
| View children | ✅ | ✅ | ✅ | ✅ |

**Status**: ✅ PASS - RBAC consistently enforced

### VI. Schema Evolution ✅

**Design Review**:
- Hibernate auto-migration: Adds `parent_id` and `version` columns automatically
- Constraints: Foreign key, unique constraint, index defined in entity annotations
- Migration safety: ✅ Non-destructive (nullable column, default values), existing workgroups become root-level
- Data loss prevention: No cascade delete, child promotion preserves all data

**Migration Script** (auto-generated by Hibernate):
```sql
ALTER TABLE workgroup
ADD COLUMN parent_id BIGINT NULL,
ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
ADD CONSTRAINT fk_workgroup_parent FOREIGN KEY (parent_id) REFERENCES workgroup(id);

CREATE INDEX idx_workgroup_parent ON workgroup(parent_id);
ALTER TABLE workgroup ADD CONSTRAINT uk_workgroup_parent_name UNIQUE (parent_id, name);
```

**Status**: ✅ PASS - Safe automated migration

---

## Final Gate Result

**Overall Status**: ✅ PASS - All constitutional requirements met

**No Violations**: No entries in Complexity Tracking section required

**Ready for Phase 2**: Proceed to `/speckit.tasks` for task generation

---

**Planning Complete**: All design artifacts generated, constitution compliance verified.
