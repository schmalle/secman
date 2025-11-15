# Implementation Plan: Maintenance Popup Banner

**Branch**: `047-maintenance-popup` | **Date**: 2025-11-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/047-maintenance-popup/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Implement a configurable maintenance banner system that allows administrators to create, schedule, and manage maintenance notifications displayed on the start/login page. The system will support time-based scheduling with timezone-aware display, multiple concurrent banners stacked vertically, and responsive design across all devices. The feature enables proactive communication to users about system maintenance without requiring code deployments.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), JavaScript/TypeScript (frontend with Astro 5.15 + React 19)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA (backend), Astro 5.15, React 19, Bootstrap 5.3 (frontend), Axios (API client)
**Storage**: MariaDB 12 (MaintenanceBanner entity with JPA)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (backend), Modern browsers (Chrome, Firefox, Safari, Edge) for frontend
**Project Type**: Web application (backend + frontend)
**Performance Goals**:
- API response time <100ms for banner queries
- Banner visibility check <50ms on page load
- Support up to 100 concurrent banners without degradation
**Constraints**:
- Banner display within 5 seconds of scheduled start time (SC-002)
- <1 minute timing variance for banner activation/deactivation (SC-005)
- Responsive design from 320px mobile to 4K desktop (SC-003)
**Scale/Scope**:
- Expected usage: 1-5 active banners typically, max 100 total banners in database
- Admin users: 5-10 concurrent administrators
- End users: Visible to all authenticated and unauthenticated visitors

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅

**Compliance**:
- ✅ File uploads: Not applicable (no file uploads in this feature)
- ✅ Input sanitization: Banner message text must be sanitized to prevent XSS
- ✅ RBAC enforcement: Admin-only access to banner management via @Secured("ADMIN")
- ✅ Sensitive data: No sensitive data in banners; error messages will not expose internals
- ✅ Authentication: Uses existing JWT in sessionStorage pattern

**Actions Required**:
- Sanitize banner message text on backend before storage
- Apply @Secured("ADMIN") to all management endpoints
- Implement XSS prevention for banner display on frontend

### II. Test-Driven Development (NON-NEGOTIABLE) ✅

**Compliance**:
- ✅ Contract tests: Will write OpenAPI contract tests for all endpoints first
- ✅ Integration tests: Will test banner display logic and timezone conversion
- ✅ Unit tests: Will test business logic (banner activation status, time range validation)
- ✅ Test frameworks: JUnit 5 + MockK (backend), Playwright (frontend)
- ✅ Coverage target: ≥80%

**Actions Required**:
- Write failing tests before implementing MaintenanceBanner entity
- Write failing tests before implementing MaintenanceBannerController
- Write failing tests before implementing frontend banner display component

### III. API-First ✅

**Compliance**:
- ✅ RESTful design: Will use standard REST patterns (GET, POST, PUT, DELETE)
- ✅ OpenAPI documentation: Will maintain Swagger docs for all endpoints
- ✅ Versioning: New endpoints, no breaking changes to existing APIs
- ✅ Error formats: Will use consistent error response structure
- ✅ HTTP status codes: Appropriate codes (200, 201, 400, 403, 404, 500)
- ✅ Backward compatibility: New feature, no impact on existing APIs

**Actions Required**:
- Define OpenAPI/Swagger spec for all endpoints in contracts/
- Implement consistent error handling for validation failures

### IV. User-Requested Testing ✅

**Compliance**:
- ✅ Test planning: Will only include test tasks if user explicitly requests
- ✅ Test preparation: Following TDD principle (write tests first), but not planning specific test cases unless requested
- ✅ Test frameworks: Required frameworks (JUnit, Playwright) will be used

**Actions Required**:
- Write tests as part of TDD workflow (tests before implementation)
- Do not create detailed test plans in tasks.md unless user requests

### V. Role-Based Access Control (RBAC) ✅

**Compliance**:
- ✅ @Secured annotations: All admin endpoints will use @Secured("ADMIN")
- ✅ Role checking: Frontend will check for ADMIN role before showing management UI
- ✅ Service layer: Authorization checks in service layer, not just controller
- ✅ Data filtering: Banner display is public (no filtering needed for viewing)
- ✅ Workgroup filtering: Not applicable (banners are system-wide)

**Actions Required**:
- Apply @Secured(SecurityRule.IS_AUTHENTICATED, "ADMIN") to create/update/delete endpoints
- Implement frontend role checks for admin banner management interface
- Public GET endpoint for active banners (no authentication required)

### VI. Schema Evolution ✅

**Compliance**:
- ✅ Hibernate auto-migration: Will use JPA annotations for MaintenanceBanner entity
- ✅ Database constraints: Will define NOT NULL, UNIQUE, and temporal constraints
- ✅ Foreign keys: Will reference User entity for createdBy field
- ✅ Indexes: Will index startTime and endTime for efficient time-range queries
- ✅ Migration testing: Will test in development before production
- ✅ Data loss prevention: New entity, no data loss risk

**Actions Required**:
- Define MaintenanceBanner entity with proper JPA annotations
- Add indexes on startTime, endTime for query performance
- Test migration in development environment

**GATE RESULT**: ✅ PASSED - All constitutional requirements can be met

---

## Post-Design Constitution Re-Check

*Re-evaluated after Phase 1 design (data-model.md, contracts, research)*

### I. Security-First ✅

**Design Confirmation**:
- ✅ XSS prevention implemented with OWASP Java HTML Sanitizer (research.md)
- ✅ @Secured("ADMIN") annotations defined in API contract (maintenance-banner-api.yaml)
- ✅ Input validation defined in MaintenanceBannerRequest DTO (data-model.md)
- ✅ Public GET endpoint safe (no auth required, read-only)

### II. Test-Driven Development ✅

**Design Confirmation**:
- ✅ Test file structure defined in quickstart.md
- ✅ Example test patterns provided (repository, controller, service, E2E)
- ✅ Test workflow documented (write tests first, then implement)

### III. API-First ✅

**Design Confirmation**:
- ✅ Complete OpenAPI 3.0 specification created (contracts/maintenance-banner-api.yaml)
- ✅ 5 RESTful endpoints defined with full request/response schemas
- ✅ Error handling patterns documented
- ✅ Security schemes defined (Bearer JWT)

### IV. User-Requested Testing ✅

**Design Confirmation**:
- ✅ Test frameworks identified (JUnit 5, MockK, Playwright)
- ✅ Test examples provided for reference (quickstart.md)
- ✅ No proactive test planning in tasks.md (awaiting /speckit.tasks)

### V. Role-Based Access Control ✅

**Design Confirmation**:
- ✅ RBAC patterns defined in API contract (401, 403 responses)
- ✅ Public vs. Admin endpoints clearly separated
- ✅ Service layer authorization documented (quickstart.md pattern #3)

### VI. Schema Evolution ✅

**Design Confirmation**:
- ✅ Complete JPA entity definition with indexes (data-model.md)
- ✅ Migration strategy documented (Hibernate auto-migration)
- ✅ Expected DDL provided for review
- ✅ Constraints defined (CHECK, FOREIGN KEY, NOT NULL)

**FINAL GATE RESULT**: ✅ PASSED - Design artifacts confirm constitutional compliance

---

## Project Structure

### Documentation (this feature)

```text
specs/047-maintenance-popup/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── maintenance-banner-api.yaml
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
# Web application structure (existing codebase)
src/backendng/src/main/kotlin/com/secman/
├── domain/
│   └── MaintenanceBanner.kt          # New JPA entity
├── repository/
│   └── MaintenanceBannerRepository.kt # New repository
├── service/
│   └── MaintenanceBannerService.kt    # New service with business logic
├── controller/
│   └── MaintenanceBannerController.kt # New REST controller
└── dto/
    ├── MaintenanceBannerRequest.kt    # Create/update DTO
    └── MaintenanceBannerResponse.kt   # Response DTO

src/backendng/src/test/kotlin/com/secman/
├── controller/
│   └── MaintenanceBannerControllerTest.kt
├── service/
│   └── MaintenanceBannerServiceTest.kt
└── repository/
    └── MaintenanceBannerRepositoryTest.kt

src/frontend/src/
├── components/
│   ├── MaintenanceBanner.tsx          # Display component (public)
│   └── admin/
│       ├── MaintenanceBannerList.tsx  # Admin list view
│       └── MaintenanceBannerForm.tsx  # Admin create/edit form
├── pages/
│   ├── index.astro                    # Modified: add banner display
│   └── admin/
│       └── maintenance-banners.astro  # New admin management page
└── services/
    └── maintenanceBannerService.ts    # API client

src/frontend/tests/
└── maintenance-banner.spec.ts         # Playwright E2E tests
```

**Structure Decision**: This feature follows the existing web application structure with separate backend (Kotlin/Micronaut) and frontend (Astro + React) directories. The backend uses the standard Domain → Repository → Service → Controller pattern. The frontend uses React components within Astro pages, with Bootstrap 5.3 for styling.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Not applicable - all constitutional requirements can be met without violations.
