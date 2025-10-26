# Implementation Plan: Outdated Assets View

**Branch**: `034-outdated-assets` | **Date**: 2025-10-26 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/034-outdated-assets/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

**Primary Requirement**: Create a performance-optimized view showing all assets with vulnerabilities exceeding the configured overdue threshold (reminder_one_days), accessible from "Vuln Management > Outdated Assets" submenu, with support for 10,000+ assets.

**Technical Approach**: Implement materialized view pattern with asynchronous background refresh jobs triggered by CLI imports and manual user actions. Use dedicated database table with indexes for sub-2-second query performance. Expose progress tracking via SSE for user feedback during long-running refresh operations. Apply existing RBAC and workgroup-based access control patterns.

## Technical Context

**Language/Version**: Kotlin 2.1 (backend), TypeScript/JavaScript (frontend)
**Primary Dependencies**: Micronaut 4.10, React 19, Astro 5.14, Bootstrap 5, Micronaut Data JPA
**Storage**: MariaDB 12 with Hibernate ORM, materialized table with indexes
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E), TDD mandatory
**Target Platform**: Web application (Linux server + modern browsers)
**Project Type**: web (frontend + backend)
**Performance Goals**: <2s page load for 10,000+ assets, <30s materialized view refresh, <1s filter/search response
**Constraints**: Must support 10,000+ assets, async refresh operations, eventual consistency acceptable, existing RBAC compliance
**Scale/Scope**: Full-stack feature: 1 materialized table, 1 refresh job entity, 4-6 API endpoints, 2-3 React components, 1 SSE endpoint

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Status**: ✅ PASS (based on CLAUDE.md constitutional principles)

- ✅ **Security-First**: Uses existing RBAC (@Secured annotations), workgroup filtering, input validation
- ✅ **TDD**: Tests before implementation (contract → unit → implement → refactor)
- ✅ **API-First**: RESTful endpoints, backward compatible with existing patterns
- ✅ **RBAC**: @Secured(ADMIN/VULN) on endpoints, role checks in UI
- ✅ **Schema Evolution**: Hibernate auto-migration for new entities
- ✅ **No violations**: Aligns with existing architecture patterns (Repository → Service → Controller)

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
│   │   ├── OutdatedAssetMaterializedView.kt       # NEW: Materialized view entity
│   │   └── MaterializedViewRefreshJob.kt          # NEW: Refresh job entity
│   ├── repository/
│   │   ├── OutdatedAssetMaterializedViewRepository.kt  # NEW: Micronaut Data repo
│   │   └── MaterializedViewRefreshJobRepository.kt     # NEW: Job tracking repo
│   ├── service/
│   │   ├── OutdatedAssetService.kt                     # NEW: Business logic
│   │   ├── MaterializedViewRefreshService.kt           # NEW: Async refresh logic
│   │   └── CrowdStrikeVulnerabilityImportService.kt    # MODIFIED: Add refresh trigger
│   └── controller/
│       ├── OutdatedAssetController.kt                  # NEW: REST endpoints
│       └── OutdatedAssetRefreshProgressHandler.kt      # NEW: SSE endpoint
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── OutdatedAssetControllerContractTest.kt      # NEW: API contract tests
    ├── service/
    │   ├── OutdatedAssetServiceTest.kt                 # NEW: Unit tests
    │   └── MaterializedViewRefreshServiceTest.kt       # NEW: Unit tests
    └── integration/
        └── OutdatedAssetIntegrationTest.kt             # NEW: E2E tests

src/frontend/
├── src/
│   ├── components/
│   │   ├── OutdatedAssetsList.tsx                      # NEW: Main list component
│   │   └── OutdatedAssetDetail.tsx                     # NEW: Detail view
│   ├── pages/
│   │   └── outdated-assets.astro                       # NEW: Main page
│   └── services/
│       └── outdatedAssetsApi.ts                        # NEW: API client
└── tests/
    └── e2e/
        └── outdated-assets.spec.ts                     # NEW: Playwright tests
```

**Structure Decision**: Web application structure with separate backend (Kotlin/Micronaut) and frontend (Astro/React). Backend follows existing layered architecture: Domain → Repository → Service → Controller. Frontend uses Astro pages with React island components.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

N/A - No constitutional violations. Feature aligns with existing architecture patterns.
