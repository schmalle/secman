# Implementation Plan: User Profile Page

**Branch**: `028-user-profile-page` | **Date**: 2025-10-19 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/028-user-profile-page/spec.md`

## Summary

Create a read-only user profile page that displays username, email, and roles for authenticated users. The page will be accessible from the "Profile" menu item in the upper-right user dropdown. Users can view their own information with proper loading states, error handling, and responsive design using Bootstrap 5 badge components for role display.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA (backend), Astro 5.14, React 19, Bootstrap 5.3 (frontend), Axios (API client)
**Storage**: MariaDB 11.4 (existing User entity, no new tables required)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Web application (backend: JVM server, frontend: browser)
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)
**Performance Goals**: Profile page loads in <1 second for 95% of requests (SC-002)
**Constraints**: Read-only (no editing), authentication required, session-based data retrieval
**Scale/Scope**: Single page + 1 backend endpoint + navigation menu update

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅ PASS

- **Authentication**: Profile page requires authentication (FR-008), redirects unauthenticated users to login
- **Authorization**: Users can only view their own profile data (FR-007), retrieved from authenticated session
- **Input Sanitization**: No user input beyond navigation (read-only page)
- **Data Exposure**: No sensitive data beyond email/roles (no passwords, tokens exposed)
- **Session Security**: JWT tokens stored in sessionStorage (existing pattern)

**Status**: ✅ No violations - feature aligns with security-first principle

### II. Test-Driven Development (TDD) ✅ PASS

- **Contract Tests**: Required for new GET /api/users/profile endpoint
- **Unit Tests**: Required for frontend components (profile page, loading/error states)
- **E2E Tests**: Required for navigation flow (menu → profile page → data display)
- **Coverage Target**: ≥80% for new code
- **Red-Green-Refactor**: Tests written before implementation

**Status**: ✅ No violations - TDD workflow will be followed

### III. API-First ✅ PASS

- **RESTful Design**: New endpoint GET /api/users/profile follows REST conventions
- **Error Handling**: Proper HTTP status codes (200 OK, 401 Unauthorized, 500 Internal Server Error)
- **Backward Compatibility**: New endpoint does not modify existing APIs
- **Consistent Error Format**: Returns standard error response format

**Status**: ✅ No violations - API-first design maintained

### IV. User-Requested Testing ✅ PASS

- **Testing Strategy**: Tests will be prepared as part of TDD workflow (Principle II requirement)
- **User Request**: No explicit user request to skip testing, so standard TDD applies
- **Test Planning**: Contract/unit/E2E tests included in implementation plan

**Status**: ✅ No violations - testing follows TDD principle

### V. Role-Based Access Control (RBAC) ✅ PASS

- **Endpoint Security**: GET /api/users/profile uses @Secured(SecurityRule.IS_AUTHENTICATED)
- **No Role Restrictions**: All authenticated users can view their own profile (no specific role required)
- **Data Filtering**: Backend returns only current user's data (no cross-user access)
- **Frontend Checks**: Navigation menu already requires authentication to display

**Status**: ✅ No violations - RBAC properly enforced

### VI. Schema Evolution ✅ PASS

- **No Schema Changes**: Feature uses existing User entity (username, email, roles fields already exist)
- **No Migrations**: No new tables, columns, or indexes required
- **Existing Constraints**: Leverages existing User table structure

**Status**: ✅ No violations - no database changes needed

## Project Structure

### Documentation (this feature)

```
specs/028-user-profile-page/
├── spec.md              # Feature specification
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (technical research)
├── data-model.md        # Phase 1 output (entity mappings)
├── quickstart.md        # Phase 1 output (development guide)
├── contracts/           # Phase 1 output (API contracts)
│   └── user-profile-api.yaml
├── checklists/          # Quality validation
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
backend/src/
├── backendng/src/main/kotlin/com/secman/
│   ├── controller/
│   │   └── UserProfileController.kt        # New: GET /api/users/profile endpoint
│   ├── dto/
│   │   └── UserProfileDto.kt               # New: Profile response DTO
│   └── domain/
│       └── User.kt                          # Existing: No changes needed
└── backendng/src/test/kotlin/com/secman/
    └── contract/
        └── UserProfileContractTest.kt       # New: Contract tests for profile endpoint

frontend/src/
├── components/
│   └── UserProfile.tsx                      # New: Profile page component with loading/error states
├── pages/
│   └── profile.astro                        # New: Profile page route
├── services/
│   └── userProfileService.ts                # New: API client for profile endpoint
└── layouts/
    └── Header.tsx                           # Modified: Add "Profile" menu item to user dropdown

frontend/tests/e2e/
└── profile.spec.ts                          # New: E2E tests for profile navigation and display
```

**Structure Decision**: This is a web application with separate backend (Micronaut/Kotlin) and frontend (Astro/React) directories. The backend provides RESTful APIs consumed by the frontend. All new code follows existing patterns established in features 001-027.

## Complexity Tracking

*No constitutional violations - this section left empty.*
