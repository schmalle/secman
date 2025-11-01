# Implementation Plan: Last Admin Protection

**Branch**: `037-last-admin-protection` | **Date**: 2025-10-31 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/037-last-admin-protection/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

This feature implements critical system protection to prevent deletion or role removal of the last remaining ADMIN user, ensuring the system never enters an unrecoverable state without administrator access. The protection applies to direct user deletion, bulk deletion operations, and ADMIN role removal scenarios, with clear error messaging and appropriate HTTP status codes for all blocked operations.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, MariaDB 11.4
**Storage**: MariaDB 11.4 (existing `users` and `user_roles` tables, no schema changes required)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (backend), modern browsers (frontend)
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)
**Performance Goals**: Admin count validation <50ms, bulk operation validation <2s for 100 users
**Constraints**: Must be transactional, must handle concurrent operations safely
**Scale/Scope**: Affects UserService, UserController, and UserDeletionValidator; extends existing user management

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅
- **Requirement**: RBAC enforced at API endpoint and UI level
- **Compliance**: DELETE /api/users/{id} already has @Secured("ADMIN"), will extend validation
- **Action**: Add admin count check before deletion, return 409 Conflict for blocked operations

### II. Test-Driven Development ✅
- **Requirement**: Contract tests → Unit tests → Implementation → Refactor
- **Compliance**: Will follow TDD strictly:
  1. Contract tests for UserController delete/update endpoints (failing)
  2. Unit tests for UserDeletionValidator.validateLastAdminProtection (failing)
  3. Unit tests for UserService enhanced methods (failing)
  4. Implementation to make tests pass
- **Coverage Target**: ≥80%

### III. API-First ✅
- **Requirement**: RESTful design, consistent error format, appropriate status codes
- **Compliance**:
  - Uses existing DELETE /api/users/{id}
  - Returns 409 Conflict with structured error for last admin deletion
  - Follows existing error response pattern from UserDeletionValidator
- **Backward Compatibility**: Extends existing validation, no breaking changes

### IV. User-Requested Testing ✅
- **Requirement**: Test planning only when explicitly requested
- **Compliance**: TDD tests required per Principle II, but E2E test planning deferred unless requested
- **Status**: Contract and unit tests are mandatory per TDD; Playwright E2E tests optional

### V. Role-Based Access Control ✅
- **Requirement**: @Secured annotations, service-layer authorization checks
- **Compliance**: User deletion already requires ADMIN role, validation occurs in service layer
- **Action**: Enhance UserDeletionValidator to include admin count check

### VI. Schema Evolution ✅
- **Requirement**: Hibernate auto-migration, explicit constraints
- **Compliance**: No schema changes required - uses existing User entity with roles collection
- **Action**: None (no database changes)

**GATE RESULT**: ✅ **PASSED** - All constitutional requirements met, no violations to justify

## Project Structure

### Documentation (this feature)

```text
specs/037-last-admin-protection/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── user-management-api.yaml
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   └── User.kt                          # Existing entity (no changes)
│   ├── repository/
│   │   └── UserRepository.kt                # Existing repository (no changes)
│   ├── service/
│   │   ├── UserService.kt                   # Extend with admin count logic
│   │   └── UserDeletionValidator.kt         # Extend with last admin check
│   ├── controller/
│   │   └── UserController.kt                # Update error handling for 409 responses
│   └── exception/
│       └── LastAdminProtectionException.kt  # New custom exception
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── UserControllerContractTest.kt    # New contract tests for protection
    ├── service/
    │   ├── UserServiceTest.kt               # Extend with admin count tests
    │   └── UserDeletionValidatorTest.kt     # Extend with last admin tests
    └── integration/
        └── UserManagementIntegrationTest.kt # New integration tests for protection

src/frontend/src/
├── components/
│   └── UserManagement.tsx                   # Update error display for 409 responses
└── pages/
    └── admin/
        └── users.astro                      # Admin user management page (existing)
```

**Structure Decision**: This is a web application with separate backend and frontend. The backend follows Micronaut's layered architecture (Domain → Repository → Service → Controller), and the frontend uses Astro with React islands for interactive components. All changes are enhancements to existing layers, with no new architectural components required.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations - table remains empty.

---

## Post-Phase 1 Constitution Re-Evaluation

*Re-checked after completing Phase 1 (Data Model, Contracts, Quickstart)*

### Design Verification

#### I. Security-First ✅
- **Verified**: HTTP 409 Conflict status code for constraint violations
- **Verified**: Structured error responses with blockingReferences
- **Verified**: Service-layer validation before database operations
- **Status**: ✅ COMPLIANT - No security concerns

#### II. Test-Driven Development ✅
- **Verified**: Quickstart provides contract-first test examples
- **Verified**: MockK-based service layer tests documented
- **Verified**: TDD workflow clearly defined (Red → Green → Refactor)
- **Status**: ✅ COMPLIANT - TDD approach enforced

#### III. API-First ✅
- **Verified**: OpenAPI 3.0 contract complete in contracts/user-management-api.yaml
- **Verified**: Consistent error response schemas defined
- **Verified**: HTTP status codes documented (200, 400, 404, 409, 500)
- **Verified**: No breaking changes to existing endpoints
- **Status**: ✅ COMPLIANT - API contract complete

#### IV. User-Requested Testing ✅
- **Verified**: Contract and unit tests included per TDD principle
- **Verified**: E2E tests marked as optional in quickstart
- **Status**: ✅ COMPLIANT - Testing requirements met

#### V. Role-Based Access Control ✅
- **Verified**: All endpoints secured with @Secured("ADMIN")
- **Verified**: Service-layer validation checks admin role
- **Status**: ✅ COMPLIANT - RBAC maintained

#### VI. Schema Evolution ✅
- **Verified**: Zero schema changes (uses existing User entity)
- **Verified**: No migration required
- **Status**: ✅ COMPLIANT - No schema impact

### Architecture Verification

**Data Model**: ✅
- No new entities or tables
- Extends existing ValidationResult DTO pattern
- Query performance acceptable (<50ms for 1000 users)

**API Contracts**: ✅
- Complete OpenAPI specification
- Error response schemas defined
- Example payloads provided

**Testing Strategy**: ✅
- Contract tests for HTTP endpoints
- Unit tests for service layer
- Integration tests optional but recommended

**Performance**: ✅
- Admin count query: O(n) with n=users, acceptable for scale
- Validation overhead: <50ms per operation
- No caching needed at current scale

### Final Gate Assessment

**GATE RESULT**: ✅ **RE-CONFIRMED PASS**

All constitutional principles maintained after Phase 1 design. No violations detected. Architecture decisions align with existing patterns. Ready for Phase 2 (Tasks Generation).

**Recommendation**: Proceed to `/speckit.tasks` to generate implementation task list.
