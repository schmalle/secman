# Implementation Plan: Asset and Workgroup Criticality Classification

**Branch**: `039-asset-workgroup-criticality` | **Date**: 2025-11-01 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/039-asset-workgroup-criticality/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Implement a two-tier criticality classification system for workgroups and assets. Workgroups define baseline criticality (CRITICAL/HIGH/MEDIUM/LOW) inherited by assets; assets can override with explicit criticality. System includes UI for setting/viewing criticality, filtering/sorting capabilities, integration with notification system (Feature 035) for prioritized alerts, and dashboard/reporting enhancements (Features 034/036) for criticality-based analytics.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), JavaScript ES2022 (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, MariaDB 11.4, Astro 5.14, React 19, Bootstrap 5.3, Axios
**Storage**: MariaDB 11.4 (2 new columns: workgroup.criticality, asset.criticality)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (backend), modern browsers (frontend - Chrome 90+, Firefox 88+, Safari 14+)
**Project Type**: web (Astro/React frontend + Micronaut backend)
**Performance Goals**: <2s asset list filtering (10K assets), <3s dashboard load (1K assets), <5s workgroup criticality propagation
**Constraints**: <200ms API response time p95, <5 second propagation for bulk criticality changes
**Scale/Scope**: 10,000 assets, 100 workgroups, 5 user stories (P1-P5 prioritized), 39 functional requirements

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Pre-Phase 0 Check

| Principle | Compliance Status | Notes |
|-----------|------------------|-------|
| **I. Security-First** | ✅ PASS | - RBAC enforced (FR-021, FR-022: ADMIN/VULN roles required for modifications)<br>- Input validation via enum constraints (FR-008, FR-009)<br>- No file uploads in this feature<br>- Workgroup-based access control maintained (FR-024) |
| **II. Test-Driven Development** | ✅ PASS | - Contract tests required for 2 new endpoints (update workgroup, update asset)<br>- Unit tests required for inheritance logic (FR-005, FR-006, FR-007)<br>- Integration tests required for notification integration (Feature 035)<br>- Target: ≥80% coverage<br>- Backend: JUnit 5 + MockK<br>- Frontend: Playwright E2E |
| **III. API-First** | ✅ PASS | - RESTful updates to existing endpoints (PUT /api/workgroups/{id}, PUT /api/assets/{id})<br>- Backward compatible: new optional field (asset.criticality nullable)<br>- Consistent error formats for validation failures (FR-037)<br>- Standard HTTP status codes (200 OK, 400 Bad Request, 409 Conflict) |
| **IV. User-Requested Testing** | ✅ PASS | - Test planning will only occur if user explicitly requests<br>- Testing frameworks already in place (JUnit, MockK, Playwright)<br>- TDD principle still enforced (tests written before implementation) |
| **V. RBAC** | ✅ PASS | - @Secured("ADMIN") for workgroup criticality changes (FR-021)<br>- @Secured annotations for asset criticality (ADMIN/VULN) (FR-022)<br>- Service-layer authorization checks required<br>- Workgroup-based filtering preserved (FR-024)<br>- All authenticated users can view (FR-023) |
| **VI. Schema Evolution** | ✅ PASS | - Hibernate auto-migration for 2 new columns<br>- NOT NULL constraint on workgroup.criticality (default MEDIUM)<br>- Nullable asset.criticality (inheritance indicator)<br>- ENUM type constraints for data integrity (FR-008)<br>- Migration strategy defined (FR-032, FR-033, FR-034) |

**Overall Pre-Phase 0 Status**: ✅ **PASS** - All constitutional principles satisfied. No violations requiring justification.

### Post-Phase 1 Re-check

| Principle | Compliance Status | Notes |
|-----------|------------------|-------|
| **I. Security-First** | ✅ PASS | - Data model enforces RBAC (criticality modification restricted by role)<br>- Enum constraints prevent injection (CHECK constraints in database)<br>- Input validation via JPA annotations and enum type safety<br>- No sensitive data in criticality fields |
| **II. Test-Driven Development** | ✅ PASS | - Quickstart includes TDD workflow (test-first examples for all layers)<br>- Contract tests defined in quickstart (WorkgroupControllerTest, AssetControllerTest)<br>- Unit tests defined for domain logic (CriticalityTest, AssetTest inheritance)<br>- Integration tests planned for notification routing<br>- Coverage target: ≥80% maintained |
| **III. API-First** | ✅ PASS | - OpenAPI contracts generated (workgroup-api.yaml, asset-api.yaml)<br>- RESTful design preserved (PUT for updates, GET with query params)<br>- Backward compatible: new optional fields, existing endpoints enhanced<br>- Consistent error responses (400, 401, 403, 404, 409)<br>- Version compatibility maintained (minor enhancement, no breaking changes) |
| **IV. User-Requested Testing** | ✅ PASS | - Testing frameworks used per TDD requirement (JUnit, MockK, Playwright)<br>- Test cases provided in quickstart for implementation guidance<br>- No premature test task generation (tasks.md not created by this command)<br>- Tests written first per TDD workflow |
| **V. RBAC** | ✅ PASS | - Data model enforces role separation (ADMIN for workgroups, ADMIN/VULN for assets)<br>- API contracts specify @Secured annotations<br>- Service-layer authorization preserved (workgroup-based access control)<br>- Frontend role checks maintained (criticality dropdowns hidden for non-authorized users) |
| **VI. Schema Evolution** | ✅ PASS | - Data model defines Hibernate-compatible schema changes<br>- Migration strategy documented (auto-migration with DEFAULT values)<br>- Indexes specified for performance (idx_workgroup_criticality, idx_asset_criticality)<br>- Backward compatible migration (nullable asset.criticality, NOT NULL workgroup.criticality with default)<br>- Rollback plan defined (DROP COLUMN) |

**Overall Post-Phase 1 Status**: ✅ **PASS** - All design artifacts comply with constitutional principles. Data model, API contracts, and quickstart guide maintain security-first approach, TDD workflow, API-first design, RBAC enforcement, and schema evolution best practices.

## Project Structure

### Documentation (this feature)

```text
specs/039-asset-workgroup-criticality/
├── spec.md              # Feature specification (complete)
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (to be generated)
├── data-model.md        # Phase 1 output (to be generated)
├── quickstart.md        # Phase 1 output (to be generated)
├── contracts/           # Phase 1 output (to be generated)
│   ├── workgroup-api.yaml
│   └── asset-api.yaml
├── checklists/          # Validation checklists
│   └── requirements.md  # Specification quality checklist (complete)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
# Web application structure (existing)
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   ├── Workgroup.kt           # MODIFY: Add criticality enum + field
│   │   ├── Asset.kt                # MODIFY: Add criticality enum + field
│   │   └── Criticality.kt          # NEW: Shared enum (or nested in entities)
│   ├── controller/
│   │   ├── WorkgroupController.kt  # MODIFY: Update PUT endpoint
│   │   └── AssetController.kt      # MODIFY: Update PUT endpoint, add criticality to responses
│   ├── service/
│   │   ├── WorkgroupService.kt     # MODIFY: Add criticality propagation logic
│   │   ├── AssetService.kt         # MODIFY: Add inheritance calculation logic
│   │   └── NotificationService.kt  # MODIFY: Add criticality-based routing (Feature 035 integration)
│   └── repository/
│       ├── WorkgroupRepository.kt  # EXISTING: No changes needed (Micronaut Data handles schema)
│       └── AssetRepository.kt      # EXISTING: No changes needed
└── src/test/kotlin/com/secman/
    ├── contract/
    │   ├── WorkgroupControllerTest.kt  # MODIFY: Add criticality tests
    │   └── AssetControllerTest.kt      # MODIFY: Add criticality tests
    ├── service/
    │   ├── WorkgroupServiceTest.kt     # MODIFY: Add propagation tests
    │   └── AssetServiceTest.kt         # NEW: Add inheritance logic tests
    └── integration/
        └── CriticalityIntegrationTest.kt  # NEW: Test full criticality workflow

src/frontend/
├── src/
│   ├── components/
│   │   ├── WorkgroupManagement.tsx     # MODIFY: Add criticality dropdown, badge display, filter
│   │   ├── AssetManagement.tsx         # MODIFY: Add criticality dropdown, badge display, filter, inheritance indicator
│   │   ├── CriticalityBadge.tsx        # NEW: Reusable badge component (color + icon + text)
│   │   └── OutdatedAssetsDashboard.tsx # MODIFY: Add criticality filter (Feature 034 integration)
│   ├── pages/
│   │   └── (existing pages - no new pages)
│   └── services/
│       └── api.ts                       # MODIFY: Add criticality fields to API types
└── tests/
    └── e2e/
        └── criticality.spec.ts          # NEW: Playwright E2E tests for criticality workflows

# Database (MariaDB schema changes via Hibernate)
# - workgroup table: ADD COLUMN criticality VARCHAR(20) NOT NULL DEFAULT 'MEDIUM'
# - asset table: ADD COLUMN criticality VARCHAR(20) NULL
```

**Structure Decision**: Web application (Option 2) - Feature extends existing backend (Micronaut/Kotlin) and frontend (Astro/React) with new criticality functionality. No new directories required; changes are localized to existing domain entities, controllers, services, and UI components.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

*Not applicable - all constitutional checks passed.*
