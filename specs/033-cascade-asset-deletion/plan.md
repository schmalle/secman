# Implementation Plan: Cascade Asset Deletion with Related Data

**Branch**: `033-cascade-asset-deletion` | **Date**: 2025-10-24 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/033-cascade-asset-deletion/spec.md`

## Summary

Implement complete cascade deletion for Asset entities to automatically delete all related vulnerabilities, ASSET-type vulnerability exceptions, and vulnerability exception requests when an asset is removed. This ensures database integrity by preventing orphaned records while preserving global exceptions (IP/PRODUCT types) and immutable audit trails. The solution includes pessimistic row-level locking for concurrency control, pre-flight validation for large datasets, detailed error reporting, and real-time progress updates for bulk operations.

**Technical Approach**: Enhance existing AssetBulkDeleteService and add new AssetCascadeDeleteService to manage dependency-ordered deletion (VulnerabilityExceptionRequests → VulnerabilityExceptions (ASSET-type only) → Vulnerabilities → Asset) within transactional boundaries. Implement database-level pessimistic locking (SELECT FOR UPDATE), pre-flight count queries with timeout estimation, and Server-Sent Events (SSE) for bulk operation progress streaming to UI.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, React 19, Bootstrap 5.3, Axios
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (backend), modern browsers (frontend: Chrome, Firefox, Safari, Edge)
**Project Type**: web (backend + frontend)
**Performance Goals**: Bulk deletion of 100 assets in <30s, UI response <2s, progress updates real-time via SSE
**Constraints**: 60-second database transaction timeout, pessimistic locking may cause wait timeouts, real-time progress requires connection management
**Scale/Scope**: Assets with up to 1000+ vulnerabilities per asset, bulk operations up to 100 assets, concurrent deletion prevention required

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Principle I: Security-First ✅

- **File uploads**: N/A - No file uploads in this feature
- **Input sanitization**: ✅ Asset IDs validated as numeric, error messages sanitized (no stack traces exposed)
- **RBAC enforcement**: ✅ Asset deletion requires ADMIN role (existing RBAC), bulk operations ADMIN-only
- **Sensitive data**: ✅ Audit logs contain only IDs and counts, no sensitive field values
- **Authentication**: ✅ All endpoints use existing @Secured annotations

**Status**: PASS

### Principle II: Test-Driven Development (NON-NEGOTIABLE) ✅

- **Contract tests**: Required for new cascade deletion endpoint and enhanced bulk delete endpoint
- **Integration tests**: Required for transaction rollback scenarios, concurrent deletion locking
- **Unit tests**: Required for AssetCascadeDeleteService, pre-flight validation logic, audit log generation
- **TDD cycle**: Red-Green-Refactor strictly followed
- **Coverage target**: ≥80% for all new services

**Status**: PASS (tests will be written first per TDD)

### Principle III: API-First ✅

- **RESTful design**: Enhance existing DELETE /api/assets/{id} and DELETE /api/assets/bulk endpoints
- **OpenAPI documentation**: Will update Swagger annotations for enhanced endpoints
- **Backward compatibility**: DELETE /api/assets/{id} behavior enhanced (not breaking), bulk endpoint response format updated with progress streaming
- **Consistent errors**: All endpoints return structured error format with type, cause, suggested action
- **HTTP status codes**: 200 (success), 409 (conflict/locked), 422 (timeout warning), 500 (server error)

**Status**: PASS

### Principle IV: User-Requested Testing ✅

- **Test planning**: Test tasks will NOT be auto-generated in tasks.md unless user explicitly requests
- **TDD compliance**: Tests will be written first during implementation (separate from planning)
- **Test frameworks**: JUnit 5, MockK, Playwright remain required for TDD execution

**Status**: PASS (testing framework setup required, test planning deferred to user request)

### Principle V: Role-Based Access Control (RBAC) ✅

- **@Secured annotations**: DELETE /api/assets/{id} already has @Secured("ADMIN"), bulk endpoint same
- **Roles**: ADMIN role required for all deletion operations
- **Frontend checks**: UI delete buttons already render based on role (no changes needed)
- **Service layer checks**: AssetCascadeDeleteService will verify RBAC before operations
- **Workgroup filtering**: Asset visibility already respects workgroups (deletion inherits same rules)

**Status**: PASS

### Principle VI: Schema Evolution ✅

- **Hibernate auto-migration**: Will use ddl-auto=update for FK constraint changes
- **Database constraints**:
  - VulnerabilityException.assetId currently NOT a FK - will remain as-is (manual deletion by query)
  - VulnerabilityExceptionRequest.vulnerability FK has ON DELETE SET NULL - will change to CASCADE or manual deletion
- **Indexes**: Existing indexes on vulnerability.asset_id, exception.asset_id sufficient for cascade queries
- **Migration testable**: Changes tested in dev environment before production
- **No data loss**: Cascade deletion is intentional data removal (explicitly approved by user)

**Status**: PASS (manual cascade deletion preferred over FK CASCADE to maintain explicit control and audit logging)

## Project Structure

### Documentation (this feature)

```
specs/033-cascade-asset-deletion/
├── spec.md              # Feature specification (already exists)
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (see below)
├── data-model.md        # Phase 1 output (see below)
├── quickstart.md        # Phase 1 output (see below)
├── contracts/           # Phase 1 output (see below)
│   └── cascade-delete-api.yaml
├── checklists/          # Quality checklists (already exists)
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
# Web application structure (backend + frontend)
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── Asset.kt (MODIFY - already exists)
│   │   ├── Vulnerability.kt (VERIFY - already exists)
│   │   ├── VulnerabilityException.kt (VERIFY - already exists)
│   │   └── VulnerabilityExceptionRequest.kt (VERIFY - already exists)
│   ├── service/
│   │   ├── AssetCascadeDeleteService.kt (NEW - core cascade logic)
│   │   ├── AssetBulkDeleteService.kt (MODIFY - enhance with cascades)
│   │   └── AssetDeletionAuditService.kt (NEW - audit logging)
│   ├── controller/
│   │   └── AssetController.kt (MODIFY - enhance delete endpoints)
│   ├── dto/
│   │   ├── CascadeDeleteSummaryDto.kt (NEW - pre-flight counts)
│   │   ├── DeletionErrorDto.kt (NEW - structured errors)
│   │   └── BulkDeleteProgressDto.kt (NEW - progress updates)
│   └── repository/
│       ├── AssetRepository.kt (VERIFY - existing)
│       ├── VulnerabilityRepository.kt (VERIFY - existing)
│       ├── VulnerabilityExceptionRepository.kt (VERIFY - existing)
│       └── VulnerabilityExceptionRequestRepository.kt (VERIFY - existing)
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── AssetCascadeDeleteContractTest.kt (NEW)
    ├── integration/
    │   ├── AssetCascadeTransactionTest.kt (NEW)
    │   └── AssetConcurrentDeletionTest.kt (NEW)
    └── service/
        ├── AssetCascadeDeleteServiceTest.kt (NEW)
        └── AssetPreFlightValidationTest.kt (NEW)

src/frontend/
├── src/
│   ├── components/
│   │   ├── AssetDeleteConfirmModal.tsx (MODIFY - add cascade counts, timeout warning)
│   │   ├── BulkDeleteProgressModal.tsx (NEW - progress streaming)
│   │   └── DeletionErrorAlert.tsx (NEW - detailed error display)
│   ├── services/
│   │   ├── assetService.ts (MODIFY - enhance delete methods)
│   │   └── bulkDeleteProgressService.ts (NEW - SSE client)
│   └── pages/
│       └── assets/[id].astro (MODIFY - update delete button handler)
└── tests/e2e/
    ├── asset-cascade-delete.spec.ts (NEW)
    └── bulk-delete-progress.spec.ts (NEW)
```

**Structure Decision**: Web application structure used because project has separate backend (Micronaut/Kotlin) and frontend (Astro/React) codebases. Backend implements cascade deletion service layer and enhanced API endpoints. Frontend adds confirmation modals with cascade warnings and SSE-based progress tracking for bulk operations. All changes integrate with existing AssetController and AssetBulkDeleteService patterns established in Feature 029 (Asset Bulk Operations).

## Complexity Tracking

*No constitutional violations - all gates pass*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| N/A | N/A | N/A |

---

## Phase 0: Research Complete ✅

**Artifacts Generated**:
- `research.md` - 5 technical decisions documented with rationale

**Key Decisions**:
1. Pessimistic row-level locking (SELECT FOR UPDATE)
2. Pre-flight COUNT queries with timeout estimation
3. Server-Sent Events for bulk progress streaming
4. Dedicated audit log entity with JSON columns
5. Manual service-layer cascade (not DB FK CASCADE)

**Gate Status**: All unknowns resolved, ready for Phase 1

---

## Phase 1: Design Complete ✅

**Artifacts Generated**:
- `data-model.md` - Entity schemas, DTOs, state transitions
- `contracts/cascade-delete-api.yaml` - OpenAPI specification
- `quickstart.md` - Developer implementation guide
- `CLAUDE.md` - Updated with feature context

**Key Outputs**:
1. **New Entity**: AssetDeletionAuditLog (immutable audit trail)
2. **New DTOs**: CascadeDeleteSummaryDto, DeletionErrorDto, BulkDeleteProgressDto, CascadeDeletionResultDto
3. **New Services**: AssetCascadeDeleteService, AssetDeletionAuditService
4. **Enhanced Services**: AssetBulkDeleteService (adds SSE streaming)
5. **Enhanced Controller**: AssetController (adds cascade endpoints)
6. **API Endpoints**:
   - `GET /api/assets/{id}/cascade-summary` - Pre-flight counts
   - `DELETE /api/assets/{id}` - Enhanced with cascade
   - `DELETE /api/assets/bulk` - Enhanced with cascade
   - `DELETE /api/assets/bulk/stream` - SSE progress streaming

**Gate Status**: Constitution check passed, design complete

---

## Re-Evaluation: Constitution Check (Post-Design) ✅

### Principle I: Security-First ✅
- Audit logs sanitized (no sensitive field values, only IDs and counts)
- Detailed error messages reveal no internal structure to non-ADMIN users
- Pessimistic locking prevents race conditions

### Principle II: TDD ✅
- Contract tests defined for all endpoints (see quickstart.md)
- Integration tests for transaction rollback, concurrent deletion
- Unit tests for cascade service, pre-flight validation
- Test-first approach documented in quickstart

### Principle III: API-First ✅
- OpenAPI spec complete (cascade-delete-api.yaml)
- Backward compatible: Enhanced existing DELETE endpoints
- Consistent error format (DeletionErrorDto)
- Standard HTTP codes: 200, 401, 403, 404, 409, 422, 500

### Principle IV: User-Requested Testing ✅
- Test framework setup documented
- Specific test cases defined in quickstart
- Test task generation deferred to /speckit.tasks (if user requests)

### Principle V: RBAC ✅
- All endpoints require ADMIN role (@Secured("ADMIN"))
- Service layer enforces same permissions
- Workgroup filtering applied (users can only delete assets they can see)

### Principle VI: Schema Evolution ✅
- Single new table: asset_deletion_audit_log
- Hibernate auto-migration will create table
- No changes to existing entity schemas
- Manual cascade deletion preserves FK integrity

**Final Status**: ALL PRINCIPLES SATISFIED ✅

---

## Next Command

**Recommended**: `/speckit.tasks`

This will generate the actionable task breakdown for implementation. Tasks will follow TDD workflow:
1. Write contract tests
2. Write unit tests
3. Implement to make tests pass
4. Refactor

**Note**: Test planning is optional per Constitution Principle IV. User can request test tasks explicitly or implement tests during development per TDD principle.
