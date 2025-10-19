# Implementation Plan: Asset Bulk Operations

**Branch**: `029-asset-bulk-operations` | **Date**: 2025-10-19 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/029-asset-bulk-operations/spec.md`

## Summary

This feature implements comprehensive asset bulk operations enabling administrators to efficiently manage large-scale asset datasets. Core capabilities include: (1) ADMIN-only bulk delete with transactional safety and confirmation modal, (2) workgroup-aware asset export to Excel format with full field coverage, (3) Excel-based asset import with validation and duplicate handling, and (4) enhanced I/O sidebar navigation for improved discoverability. The complete workflow supports export → delete → import data lifecycle with 100% data integrity guarantee.

**Technical Approach**: Extends existing AssetManagement UI component with conditional ADMIN button, adds asset import/export handlers to Import/Export components following established patterns from Features 013/016 (user mappings), leverages Apache POI for Excel processing, implements transactional bulk delete via repository methods, and updates Sidebar component with nested I/O navigation structure.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Apache POI 5.3, Apache Commons CSV 1.11.0, Astro, React 19, Bootstrap 5.3
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Web application (browser-based UI + RESTful API)
**Project Type**: web (frontend + backend)
**Performance Goals**:
- Bulk delete 10K+ assets in <30 seconds
- Export 10K assets in <15 seconds
- Import 5K assets in <60 seconds with 95%+ success rate
**Constraints**:
- Excel file size limit: 10MB (existing import infrastructure)
- Transaction timeout: 30 seconds for bulk delete
- Workgroup-based access control must be preserved
**Scale/Scope**:
- Current system: 4,403 assets (per screenshot)
- Target: Support 10K+ asset bulk operations
- 3 new API endpoints, 6 UI components modified/created

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Principle I: Security-First ✅ PASS

**Evidence**:
- FR-006: "System MUST prevent non-ADMIN users from accessing bulk delete functionality through UI or API"
- FR-011: "System MUST apply workgroup-based access control to asset exports"
- Edge case handling: "API returns 403 Forbidden" for unauthorized access
- File upload validation specified in FR-017: "validate file size, format, and required fields"

**Implementation Requirements**:
- `@Secured("ADMIN")` annotation on bulk delete endpoint
- `@Secured(SecurityRule.IS_AUTHENTICATED)` on import/export endpoints
- File validation: size (10MB max), MIME type (.xlsx), content validation
- Input sanitization for asset names, descriptions (prevent XSS/injection)
- Workgroup filtering in export query to prevent data leakage

### Principle II: Test-Driven Development (NON-NEGOTIABLE) ✅ PASS (with note)

**Evidence**:
- Spec includes 25 acceptance scenarios across 5 user stories
- Edge cases documented (10 specific scenarios)
- Success criteria are measurable (8 criteria with specific metrics)

**Implementation Requirements**:
- Contract tests for 3 new API endpoints (bulk delete, asset export, asset import)
- Unit tests for Excel parser, workgroup filter logic, duplicate detection
- Integration tests for export → delete → import workflow (US5)
- E2E tests for UI interactions (button visibility, modal confirmation, file upload)

**Note**: Per Constitution Principle IV (User-Requested Testing), test planning occurs when explicitly requested by user. Tests MUST still be written first (TDD), but detailed test case planning is deferred unless requested.

### Principle III: API-First ✅ PASS

**Evidence**:
- FR-003: RESTful endpoints for bulk operations
- Assumptions reference existing API patterns (D-004: "Existing import/export patterns")
- OpenAPI documentation implied by dependency on existing API infrastructure

**Implementation Requirements**:
- `DELETE /api/assets/bulk` - Bulk delete endpoint
- `GET /api/assets/export` - Asset export endpoint (or extend existing export controller)
- `POST /api/import/upload-assets-xlsx` - Asset import endpoint
- Consistent error format: `{ "error": "message", "details": [...] }`
- HTTP status codes: 200 (success), 400 (validation), 403 (forbidden), 500 (error)
- Backward compatibility: New endpoints do not modify existing `/api/assets` behavior

### Principle IV: User-Requested Testing ✅ PASS

**Evidence**:
- No proactive test case preparation in spec
- Test requirements captured in Success Criteria for validation
- TDD framework requirements met (JUnit 5, MockK, Playwright)

**Implementation Requirements**:
- Testing frameworks remain required per TDD principle
- Test planning deferred unless user explicitly requests `/speckit.test` or similar
- When tests ARE written (per TDD), they must be written first and fail before implementation

### Principle V: Role-Based Access Control (RBAC) ✅ PASS

**Evidence**:
- FR-001: "visible only to users with ADMIN role"
- FR-006: "prevent non-ADMIN users from accessing bulk delete"
- FR-011: "workgroup-based access control to asset exports"
- Clarification: "Hide button when asset count is zero" (ADMIN-specific UI logic)

**Implementation Requirements**:
- Bulk delete: ADMIN role only (`@Secured("ADMIN")`)
- Export: All authenticated users (`@Secured(SecurityRule.IS_AUTHENTICATED)`) with workgroup filtering
- Import: All authenticated users with creator tracking (FR-022)
- Frontend role checks: `isAdmin()` utility for button visibility
- Service layer: Workgroup filtering in AssetService.findByWorkgroups()
- Authorization verification: Controller + Service layer checks

### Principle VI: Schema Evolution ✅ PASS

**Evidence**:
- Assumption A-010: "Cascade deletion behavior already exists for individual asset deletion"
- Dependency D-003: "Current Asset entity structure supports bulk operations"
- No schema changes required (uses existing Asset, Workgroup, User entities)

**Implementation Requirements**:
- No new entities or tables required
- Existing relationships (Asset → Workgroup, Asset → Vulnerability, Asset → ScanResult) support cascade delete
- Hibernate auto-migration handles any minor adjustments
- Database constraints already defined in Asset entity annotations

**GATE STATUS**: ✅ ALL PRINCIPLES PASS - Proceed to Phase 0 Research

## Project Structure

### Documentation (this feature)

```
specs/029-asset-bulk-operations/
├── plan.md              # This file (/speckit.plan command output)
├── spec.md              # Feature specification (created by /speckit.specify)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── bulk-delete.yaml     # DELETE /api/assets/bulk
│   ├── asset-export.yaml    # GET /api/assets/export
│   └── asset-import.yaml    # POST /api/import/upload-assets-xlsx
├── checklists/          # Quality validation checklists
│   └── requirements.md  # Spec quality checklist (created by /speckit.specify)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
# Web application structure (frontend + backend)

src/backendng/src/main/kotlin/com/secman/
├── controller/
│   ├── AssetController.kt           # MODIFIED: Add bulk delete endpoint
│   ├── ImportController.kt          # MODIFIED: Add asset import endpoint
│   └── ExportController.kt          # MODIFIED: Add asset export endpoint (or create if not exists)
├── service/
│   ├── AssetService.kt              # MODIFIED: Add bulk delete with transaction
│   ├── AssetExportService.kt        # NEW: Asset export logic with workgroup filtering
│   └── AssetImportService.kt        # NEW: Asset import with validation and duplicate handling
├── domain/
│   └── Asset.kt                     # EXISTING: No changes required
└── dto/
    ├── AssetExportDto.kt            # NEW: DTO for export data
    ├── AssetImportDto.kt            # NEW: DTO for import data
    └── ImportResultDto.kt           # EXISTING: Reuse from Feature 013/016

src/backendng/src/test/kotlin/com/secman/
├── contract/
│   ├── AssetBulkDeleteContractTest.kt    # NEW: Contract tests for bulk delete
│   ├── AssetExportContractTest.kt        # NEW: Contract tests for export
│   └── AssetImportContractTest.kt        # NEW: Contract tests for import
└── service/
    ├── AssetServiceTest.kt               # MODIFIED: Add bulk delete tests
    ├── AssetExportServiceTest.kt         # NEW: Unit tests for export service
    └── AssetImportServiceTest.kt         # NEW: Unit tests for import service

src/frontend/src/
├── components/
│   ├── AssetManagement.tsx          # MODIFIED: Add bulk delete button with confirmation modal
│   ├── Import.tsx                   # MODIFIED: Add asset import handler
│   ├── Export.tsx                   # MODIFIED: Add asset export handler
│   └── Sidebar.tsx                  # MODIFIED: Add I/O > Import > Assets and I/O > Export > Assets links
├── services/
│   ├── assetService.ts              # MODIFIED: Add bulkDelete(), exportAssets(), importAssets()
│   └── (reuse existing auth/csrf utils)
└── utils/
    └── (reuse existing auth, permissions utilities)

src/frontend/tests/e2e/
└── asset-bulk-operations.spec.ts    # NEW: E2E tests for workflow
```

**Structure Decision**: This is a web application (Option 2: Web application) with clear frontend/backend separation. Backend uses Micronaut + Kotlin in `src/backendng/`, frontend uses Astro + React in `src/frontend/`. The structure aligns with existing Features 008 (workgroups), 013/016 (user mappings import), and 012 (release management UI). All new code follows established patterns in these directories.

## Complexity Tracking

*No constitutional violations requiring justification. All principles pass.*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| N/A       | N/A        | N/A                                 |
