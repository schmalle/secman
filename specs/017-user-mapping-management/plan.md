# Implementation Plan: User Mapping Management in User Edit Interface

**Branch**: `017-user-mapping-management` | **Date**: 2025-10-13 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/017-user-mapping-management/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

This feature extends the existing user management interface to display, add, edit, and delete user mappings (domain and AWS account associations) directly within the user edit dialog. The feature leverages the existing `UserMapping` entity and `UserMappingRepository` from Feature 013, adding new CRUD endpoints to the backend and integrating mapping management into the frontend user edit component. The primary technical approach involves extending the existing `UserController` with mapping-specific endpoints and enhancing the frontend `UserManagement` component to display and manage mappings inline.

## Technical Context

**Language/Version**: Kotlin 2.1.0 (Backend), TypeScript/JavaScript (Frontend with React 19 + Astro 5.14)  
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, MariaDB 11.4 (Backend), Axios, Bootstrap 5.3 (Frontend)  
**Storage**: MariaDB with existing `user_mapping` table (columns: id, email, aws_account_id, domain, created_at, updated_at)  
**Testing**: JUnit 5 + MockK (Backend), Playwright (Frontend E2E)  
**Target Platform**: Web application (Docker-based deployment)  
**Project Type**: Web - separate backend (Kotlin/Micronaut) and frontend (Astro/React)  
**Performance Goals**: 
- Load all mappings for a user within 2 seconds
- Add/edit/delete operations complete within 30 seconds
- Support up to 100 mappings per user without degradation  
**Constraints**: 
- Admin-only access (RBAC enforced via @Secured("ADMIN"))
- Must validate AWS Account ID format (12 digits)
- Must validate domain format (lowercase letters, numbers, dots, hyphens)
- Must prevent duplicate mappings (email + awsAccountId + domain unique constraint)  
**Scale/Scope**: 
- Expected users: ~100 administrators
- Expected mappings per user: 5-20 typical, up to 100 maximum
- Existing infrastructure: UserMapping entity, UserMappingRepository, user edit dialog

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅ PASS

- **File uploads**: N/A - This feature does not involve file uploads
- **User input sanitization**: ✅ Required - All mapping inputs (email, domain, AWS account ID) will be validated using existing domain constraints and Micronaut validation annotations
- **RBAC enforcement**: ✅ Required - All new endpoints will use @Secured("ADMIN") to restrict access
- **Sensitive data**: ✅ Required - No sensitive data logged; email/domain/AWS ID are non-sensitive identifiers
- **Token storage**: ✅ Already implemented - Frontend uses existing authentication patterns

**Status**: PASS - All security requirements align with feature design

### II. Test-Driven Development (NON-NEGOTIABLE) ✅ PASS

- **Contract tests first**: ✅ Required - Will write OpenAPI contract and integration tests before implementation
- **Integration tests**: ✅ Required - Will test UserController endpoints with UserMappingRepository
- **Unit tests**: ✅ Required - Will test validation logic and service methods
- **Red-Green-Refactor**: ✅ Committed - Tests will fail before implementation
- **Coverage target**: ✅ ≥80% for new code
- **Test frameworks**: ✅ JUnit 5 + MockK (Backend), Playwright (Frontend E2E)

**Status**: PASS - TDD workflow will be strictly followed

### III. API-First ✅ PASS

- **RESTful design**: ✅ Required - Endpoints follow REST principles (GET /api/users/{userId}/mappings, POST, PUT, DELETE)
- **OpenAPI documentation**: ✅ Required - Will create contract in contracts/user-mappings-api.yml
- **Consistent errors**: ✅ Required - Will use existing error response patterns from UserController
- **HTTP status codes**: ✅ Required - 200 OK, 201 Created, 400 Bad Request, 403 Forbidden, 404 Not Found
- **Backward compatibility**: ✅ N/A - New endpoints do not affect existing APIs

**Status**: PASS - API-first approach will be used

### IV. Docker-First ✅ PASS

- **Containerization**: ✅ N/A - No new services required; extends existing backend container
- **Multi-arch support**: ✅ N/A - Uses existing Docker setup
- **Environment config**: ✅ N/A - No new configuration needed
- **Health checks**: ✅ N/A - Existing health checks sufficient

**Status**: PASS - Leverages existing Docker infrastructure

### V. Role-Based Access Control (RBAC) ✅ PASS

- **@Secured annotations**: ✅ Required - All mapping endpoints will use @Secured("ADMIN")
- **Frontend role checks**: ✅ Required - UI will check for ADMIN role before rendering mapping UI
- **Service layer auth**: ✅ Required - Service methods will verify user has ADMIN role
- **Data filtering**: ✅ Required - Mappings filtered by email (matches user being edited)

**Status**: PASS - RBAC consistently enforced

### VI. Schema Evolution ✅ PASS

- **Hibernate auto-migration**: ✅ N/A - No schema changes required; uses existing user_mapping table
- **Database constraints**: ✅ Already present - Unique constraint on (email, awsAccountId, domain)
- **Foreign key relationships**: ✅ N/A - user_mapping table does not have FK to users table (intentional design)
- **Indexes**: ✅ Already present - Indexes on email, awsAccountId, domain
- **Migration testing**: ✅ N/A - No schema changes

**Status**: PASS - No schema changes required

**OVERALL GATE STATUS: ✅ PASS - All constitutional principles satisfied**

## Phase 1: Design & Contracts ✅ COMPLETE

**Date Completed**: 2025-10-13

### Artifacts Created

1. **data-model.md** ✅
   - Documented existing UserMapping entity
   - Defined DTOs: UserMappingResponse, CreateUserMappingRequest, UpdateUserMappingRequest, ErrorResponse
   - Specified validation rules and business constraints
   - Documented query patterns and repository methods
   - Defined data flow diagrams

2. **contracts/user-mappings-api.yml** ✅
   - OpenAPI 3.0.3 specification
   - 4 endpoints: GET, POST, PUT, DELETE /api/users/{userId}/mappings
   - Complete request/response schemas
   - Error response definitions
   - Security requirements (Bearer JWT)
   - Example payloads for all operations

3. **quickstart.md** ✅
   - Step-by-step TDD implementation guide
   - Backend: Service → Tests → Implementation → Controller
   - Frontend: API client → Component → E2E tests
   - Verification checklist
   - Troubleshooting guide

### Phase 1 Validation

- ✅ All artifacts generated
- ✅ Agent context updated (.github/copilot-instructions.md)
- ✅ Contracts align with research decisions
- ✅ Test-first workflow documented in quickstart
- ✅ No schema changes required (existing table)

**Ready for**: Phase 2 - Task Generation

## Project Structure

### Documentation (this feature)
```

```text
specs/017-user-mapping-management/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── user-mappings-api.yml  # OpenAPI spec for new endpoints
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/
├── backendng/           # Kotlin/Micronaut backend
│   └── src/
│       ├── main/kotlin/com/secman/
│       │   ├── controller/
│       │   │   └── UserController.kt              # EXTEND: Add mapping endpoints
│       │   ├── domain/
│       │   │   └── UserMapping.kt                 # EXISTING: No changes needed
│       │   ├── repository/
│       │   │   └── UserMappingRepository.kt       # EXISTING: All needed queries exist
│       │   └── service/
│       │       └── UserMappingService.kt          # NEW: Business logic for CRUD operations
│       └── test/kotlin/com/secman/
│           ├── controller/
│           │   └── UserControllerMappingTest.kt   # NEW: Integration tests for mapping endpoints
│           └── service/
│               └── UserMappingServiceTest.kt      # NEW: Unit tests for service
│
└── frontend/            # Astro + React frontend
    └── src/
        ├── components/
        │   ├── UserManagement.tsx                 # EXTEND: Add mapping management UI
        │   └── UserMappingManager.tsx             # NEW: Reusable mapping CRUD component
        └── utils/
            └── api.ts                             # EXTEND: Add mapping API calls

tests/
└── frontend/
    └── user-mapping-management.spec.ts            # NEW: Playwright E2E tests
```

**Structure Decision**: Web application structure with separate backend and frontend. This aligns with the existing Secman architecture where:

- Backend (src/backendng/) provides REST API endpoints using Micronaut framework
- Frontend (src/frontend/) is an Astro application with React islands for interactive components
- The UserMapping entity and repository already exist from Feature 013 (user-mapping-upload)
- The UserController already exists and manages user CRUD operations
- We will extend existing components rather than creating parallel structures

**Key Implementation Points**:

1. **Backend Extension** (src/backendng/):
   - Extend `UserController` with 4 new endpoints for mapping CRUD
   - Create new `UserMappingService` to encapsulate business logic
   - Reuse existing `UserMappingRepository` (no changes needed)
   - Reuse existing `UserMapping` domain entity (no changes needed)

2. **Frontend Extension** (src/frontend/):
   - Extend existing `UserManagement.tsx` component to include mapping section
   - Create new `UserMappingManager.tsx` reusable component for mapping display/edit
   - Extend API utility functions to call new backend endpoints

3. **Testing**:
   - Backend: Integration tests for controller endpoints, unit tests for service
   - Frontend: Playwright E2E tests for complete user workflow

## Complexity Tracking

*No violations - all constitutional principles satisfied*

This feature does not introduce any complexity that violates the Secman Constitution:

- Extends existing UserController rather than creating new services
- Reuses existing UserMapping entity and repository
- Follows established patterns for RBAC, validation, and error handling
- No schema changes required
- Standard REST API design
- TDD workflow will be followed

**Justification**: N/A - No complexity threshold exceeded
