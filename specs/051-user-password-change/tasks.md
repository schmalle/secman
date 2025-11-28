# Tasks: User Password Change

**Input**: Design documents from `/specs/051-user-password-change/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/password-change-api.yaml

**Tests**: No test tasks included (per constitution - testing only when explicitly requested)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`
- **Migrations**: `src/backendng/src/main/resources/db/migration/`

---

## Phase 1: Setup (Database Migration)

**Purpose**: Database schema changes to support authentication source tracking

- [x] T001 Create Flyway migration V051__user_password_change.sql in src/backendng/src/main/resources/db/migration/V051__user_password_change.sql

---

## Phase 2: Foundational (Entity & DTO Changes)

**Purpose**: Core entity modifications that all user stories depend on

**CRITICAL**: These changes MUST be complete before ANY user story can be implemented

- [x] T002 Add AuthSource enum to User.kt in src/backendng/src/main/kotlin/com/secman/domain/User.kt
- [x] T003 Add authSource field to User entity in src/backendng/src/main/kotlin/com/secman/domain/User.kt
- [x] T004 Update UserProfileDto to add canChangePassword field in src/backendng/src/main/kotlin/com/secman/dto/UserProfileDto.kt
- [x] T005 Update OAuthService.createNewOidcUser() to set authSource=OAUTH in src/backendng/src/main/kotlin/com/secman/service/OAuthService.kt

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Change Own Password (Priority: P1) MVP

**Goal**: Enable authenticated users with local accounts to change their own password via the user profile page

**Independent Test**: Log in as any local user, navigate to profile, enter current password and new password twice, submit, verify success message, logout, verify only new password works

**Requirements Covered**: FR-001, FR-002, FR-003, FR-004, FR-008, FR-009, FR-010

### Backend Implementation for User Story 1

- [x] T006 [US1] Add ChangePasswordRequest DTO (inline) in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [x] T007 [US1] Add ChangePasswordResponse DTO (inline) in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [x] T008 [US1] Implement PUT /api/users/profile/change-password endpoint in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [x] T009 [US1] Add password validation logic (current password verification, confirmation matching) in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [x] T010 [US1] Add OAuth user rejection (check authSource != OAUTH) in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt

### Frontend Implementation for User Story 1

- [x] T011 [P] [US1] Update UserProfileData interface with canChangePassword in src/frontend/src/services/userProfileService.ts
- [x] T012 [P] [US1] Add ChangePasswordRequest and ChangePasswordResponse interfaces in src/frontend/src/services/userProfileService.ts
- [x] T013 [US1] Add changePassword() method to userProfileService in src/frontend/src/services/userProfileService.ts
- [x] T014 [US1] Add password change form to Security Settings card in src/frontend/src/components/UserProfile.tsx
- [x] T015 [US1] Implement form state management (currentPassword, newPassword, confirmPassword) in src/frontend/src/components/UserProfile.tsx
- [x] T016 [US1] Implement success/error message display in src/frontend/src/components/UserProfile.tsx
- [x] T017 [US1] Conditionally render password change section based on canChangePassword in src/frontend/src/components/UserProfile.tsx

**Checkpoint**: User Story 1 complete - local users can change their password via the UI

---

## Phase 4: User Story 2 - Password Validation Feedback (Priority: P2)

**Goal**: Provide clear, user-friendly validation feedback on password requirements

**Independent Test**: Attempt to submit various invalid passwords (too short, doesn't match confirmation, same as current) and verify appropriate error messages appear

**Requirements Covered**: FR-005, FR-006, FR-007

### Backend Implementation for User Story 2

- [x] T018 [US2] Add minimum length validation (8 chars) with specific error message in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [x] T019 [US2] Add same-as-current validation with specific error message in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [x] T020 [US2] Add max length validation (200 chars) with specific error message in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt

### Frontend Implementation for User Story 2

- [x] T021 [US2] Add client-side validation for minimum password length in src/frontend/src/components/UserProfile.tsx
- [x] T022 [US2] Add client-side validation for password confirmation matching in src/frontend/src/components/UserProfile.tsx
- [x] T023 [US2] Display real-time validation feedback as user types in src/frontend/src/components/UserProfile.tsx
- [x] T024 [US2] Disable submit button when validation fails in src/frontend/src/components/UserProfile.tsx

**Checkpoint**: User Story 2 complete - users receive clear feedback on password requirements

---

## Phase 5: User Story 3 - Security Audit Trail (Priority: P3)

**Goal**: Log all password change events for security audit purposes

**Independent Test**: Change a password, then check application logs for the password change event record with timestamp, user ID, and action type (but NOT the password itself)

**Requirements Covered**: FR-011

### Backend Implementation for User Story 3

- [x] T025 [US3] Inject AuditLogService into UserProfileController in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [x] T026 [US3] Add audit logging for successful password changes in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt
- [x] T027 [US3] Add audit logging for failed password change attempts in src/backendng/src/main/kotlin/com/secman/controller/UserProfileController.kt

**Checkpoint**: User Story 3 complete - all password changes are logged for security audit

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final improvements and verification

- [x] T028 Verify backend build succeeds with ./gradlew build
- [x] T029 Verify frontend build succeeds with npm run build in src/frontend
- [x] T030 Update CLAUDE.md with new endpoint documentation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies - can start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 - BLOCKS all user stories
- **Phase 3-5 (User Stories)**: All depend on Phase 2 completion
  - US1 can start immediately after Phase 2
  - US2 can start after US1 backend (T010) for validation integration
  - US3 can start after US1 backend (T010) for audit integration
- **Phase 6 (Polish)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational phase - MVP functionality
- **User Story 2 (P2)**: Extends US1 with validation feedback - builds on T008-T010
- **User Story 3 (P3)**: Extends US1 with audit logging - builds on T008-T010

### Within Each User Story

- DTOs before endpoint implementation
- Backend before frontend (API must exist before UI calls it)
- Core implementation before enhancements

### Parallel Opportunities

**Within Phase 2**:
```text
T002, T003 must be sequential (same file)
T004 can run in parallel with T002-T003 (different file)
T005 depends on T002-T003 (uses AuthSource enum)
```

**Within User Story 1**:
```text
Backend (T006-T010) must complete before frontend tests API
T011, T012 can run in parallel (both service changes)
T014-T017 must be sequential (same component file)
```

**Within User Story 2**:
```text
T018, T019, T020 can run in parallel (different validation rules in same method)
T21-T24 must be sequential (same component file)
```

---

## Parallel Example: Foundational Phase

```bash
# Can run in parallel:
Task: T004 "Update UserProfileDto"
# After T002-T003 complete:
Task: T005 "Update OAuthService"
```

## Parallel Example: User Story 1

```bash
# After backend complete (T010):
# Can run in parallel:
Task: T011 "Update UserProfileData interface"
Task: T012 "Add request/response interfaces"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001)
2. Complete Phase 2: Foundational (T002-T005)
3. Complete Phase 3: User Story 1 (T006-T017)
4. **STOP and VALIDATE**: Test password change flow end-to-end
5. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → Database and entity changes ready
2. Add User Story 1 → Core password change works → **MVP Complete**
3. Add User Story 2 → Better UX with validation feedback
4. Add User Story 3 → Security audit compliance
5. Each story adds value without breaking previous stories

### Task Count Summary

| Phase | Task Count |
|-------|------------|
| Phase 1: Setup | 1 |
| Phase 2: Foundational | 4 |
| Phase 3: User Story 1 | 12 |
| Phase 4: User Story 2 | 7 |
| Phase 5: User Story 3 | 3 |
| Phase 6: Polish | 3 |
| **Total** | **30** |

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- No test tasks included per constitution (will add if explicitly requested)
