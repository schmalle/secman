# Implementation Plan: User Password Change

**Branch**: `051-user-password-change` | **Date**: 2025-11-28 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/051-user-password-change/spec.md`

## Summary

Enable authenticated users with local accounts to change their own password via the user profile page. The feature adds a password change endpoint to UserProfileController, modifies the User entity to track authentication source (LOCAL vs OAUTH), and extends the frontend UserProfile component with a password change form.

**Key Technical Decisions**:
- Add `authSource` enum field to User entity to distinguish local vs OAuth users
- Reuse existing BCryptPasswordEncoder for password hashing
- Extend UserProfileController with PUT /api/users/profile/change-password endpoint
- Add password change form to existing Security Settings card in UserProfile.tsx

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), TypeScript (frontend with Astro 5.15 + React 19)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA (backend), Astro 5.15, React 19, Bootstrap 5.3 (frontend)
**Storage**: MariaDB 12 (existing users table, Flyway migration)
**Testing**: N/A (per constitution - testing only when explicitly requested)
**Target Platform**: Linux server (backend), Modern browsers (frontend)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Password change operation < 500ms response time
**Constraints**: BCrypt hashing adds ~100ms, acceptable for security operation
**Scale/Scope**: Single new endpoint, 2 entity modifications, 1 new UI form

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | ✅ PASS | Password hashing with BCrypt, audit logging, no password exposure |
| III. API-First | ✅ PASS | RESTful endpoint, OpenAPI documented, consistent error format |
| IV. User-Requested Testing | ✅ PASS | No test tasks included (will add only if requested) |
| V. RBAC | ✅ PASS | Endpoint uses @Secured(IS_AUTHENTICATED), no admin-level access needed |
| VI. Schema Evolution | ✅ PASS | Flyway migration V051, no data loss, additive change |

**Re-check after Phase 1 Design**: All gates continue to pass.

## Project Structure

### Documentation (this feature)

```text
specs/051-user-password-change/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Technical decisions and rationale
├── data-model.md        # Entity changes (User.authSource)
├── quickstart.md        # Developer quick reference
├── contracts/           # API specifications
│   └── password-change-api.yaml
├── checklists/
│   └── requirements.md  # Quality checklist
└── tasks.md             # Implementation tasks (created by /speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   └── User.kt              # Add AuthSource enum and authSource field
│   ├── controller/
│   │   └── UserProfileController.kt  # Add changePassword endpoint
│   ├── dto/
│   │   └── UserProfileDto.kt    # Add canChangePassword field
│   └── service/
│       └── OAuthService.kt      # Set authSource=OAUTH for new OAuth users
├── src/main/resources/
│   └── db/migration/
│       └── V051__user_password_change.sql  # Add auth_source column

src/frontend/
├── src/
│   ├── components/
│   │   └── UserProfile.tsx      # Add password change form
│   └── services/
│       └── userProfileService.ts  # Add changePassword method
```

**Structure Decision**: Web application structure (backend + frontend) - existing project layout, no new directories needed.

## Complexity Tracking

> No constitution violations - section left empty as no justifications needed.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |

## Implementation Phases

### Phase 1: Backend Core (Priority: P1)

1. **User Entity Changes**
   - Add `AuthSource` enum (LOCAL, OAUTH, HYBRID)
   - Add `authSource` field to User entity
   - Create Flyway migration V051

2. **API Endpoint**
   - Add ChangePasswordRequest DTO
   - Add ChangePasswordResponse DTO
   - Implement PUT /api/users/profile/change-password
   - Add validation logic (min 8 chars, different from current, etc.)
   - Add audit logging

3. **Profile DTO Update**
   - Add `canChangePassword` field to UserProfileDto
   - Update `fromUser()` mapping

### Phase 2: OAuth Integration (Priority: P2)

1. **OAuthService Update**
   - Set `authSource = OAUTH` when creating new OAuth users
   - Ensure existing code paths unchanged

### Phase 3: Frontend (Priority: P1)

1. **Service Layer**
   - Update UserProfileData interface with canChangePassword
   - Add changePassword() method to userProfileService

2. **UI Component**
   - Add password change form to Security Settings card
   - Implement client-side validation
   - Handle success/error states
   - Conditionally render based on canChangePassword

## Dependencies

**Internal Dependencies**:
- UserRepository (existing)
- AuditLogService (existing)
- BCryptPasswordEncoder (existing, from spring-security-crypto)

**External Dependencies**:
- None new required

## Related Documentation

- [Specification](./spec.md) - Feature requirements and acceptance criteria
- [Research](./research.md) - Technical decisions and alternatives considered
- [Data Model](./data-model.md) - Entity changes and DTOs
- [API Contract](./contracts/password-change-api.yaml) - OpenAPI specification
- [Quickstart](./quickstart.md) - Developer quick reference
