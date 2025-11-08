# Tasks: Future User Mapping Support

**Input**: Design documents from `/specs/042-future-user-mappings/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/user-mapping-api.yaml

**Tests**: Test case development excluded per user request. Tests will be written during implementation per TDD principles (Constitution II).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Web app**: `src/backendng/` (backend), `src/frontend/` (frontend)
- Paths reference actual project structure from plan.md

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and verification of existing structure

- [X] T001 Verify Kotlin/Micronaut project structure in src/backendng/
- [X] T002 Verify React/Astro frontend structure in src/frontend/
- [X] T003 [P] Review existing UserMapping entity in src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
- [X] T004 [P] Review existing UserMappingImportService in src/backendng/src/main/kotlin/com/secman/service/UserMappingImportService.kt
- [X] T005 [P] Review existing UserService in src/backendng/src/main/kotlin/com/secman/service/UserService.kt

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Backend Entity & Repository Changes

- [ ] T006 Modify UserMapping entity: Add appliedAt field (nullable Instant) in src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
- [ ] T007 Modify UserMapping entity: Make user field nullable (@ManyToOne(optional = true)) in src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
- [ ] T008 Modify UserMapping entity: Add unique constraint on email field in src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
- [ ] T009 Modify UserMapping entity: Add index on applied_at field in src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
- [ ] T010 Modify UserMapping entity: Add isFutureMapping() helper method in src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
- [ ] T011 Modify UserMapping entity: Add isAppliedMapping() helper method in src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt
- [ ] T012 Extend UserMappingRepository: Add findByEmailIgnoreCase(email: String) method in src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt
- [ ] T013 [P] Extend UserMappingRepository: Add findByAppliedAtIsNull(pageable: Pageable) method in src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt
- [ ] T014 [P] Extend UserMappingRepository: Add findByAppliedAtIsNotNull(pageable: Pageable) method in src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt
- [ ] T015 [P] Extend UserMappingRepository: Add countByAppliedAtIsNull() method in src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt
- [ ] T016 [P] Extend UserMappingRepository: Add countByAppliedAtIsNotNull() method in src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt

### Event Infrastructure

- [ ] T017 Create UserCreatedEvent data class in src/backendng/src/main/kotlin/com/secman/event/UserCreatedEvent.kt
- [ ] T018 Create UserMappingService with @Singleton annotation in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt

### Database Migration Verification

- [ ] T019 Verify Hibernate will auto-migrate user_mapping table (add applied_at column, make user_id nullable)
- [ ] T020 Test database migration in local development environment

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Upload Mappings for Future Users (Priority: P1) 🎯 MVP

**Goal**: Allow administrators to upload mapping files containing email addresses for users who don't yet exist, and automatically apply those mappings when users are created

**Independent Test**: Upload a mapping file with non-existent user emails, create those users later, verify mappings are automatically applied and grant correct asset access

### Backend: Import Service Extension

- [ ] T021 [US1] Modify ImportService: Remove user existence check to allow future users in src/backendng/src/main/kotlin/com/secman/service/ImportService.kt
- [ ] T022 [US1] Modify ImportService: Update Excel upload logic to handle mixed existing/future users in src/backendng/src/main/kotlin/com/secman/service/ImportService.kt
- [ ] T023 [US1] Modify ImportService: Update CSV upload logic to handle mixed existing/future users in src/backendng/src/main/kotlin/com/secman/service/ImportService.kt
- [ ] T024 [US1] Modify ImportService: Ensure duplicate email handling works for future users (last in file wins) in src/backendng/src/main/kotlin/com/secman/service/ImportService.kt
- [ ] T025 [US1] Verify existing validation rules maintained (email format, 12-digit AWS account, domain format, scientific notation) in src/backendng/src/main/kotlin/com/secman/service/ImportService.kt

### Backend: Automatic Mapping Application

- [ ] T026 [US1] Implement UserMappingService.applyFutureUserMapping(user: User) method in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt
- [ ] T027 [US1] Implement case-insensitive email lookup in applyFutureUserMapping in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt
- [ ] T028 [US1] Implement conflict resolution (pre-existing mapping wins) in applyFutureUserMapping in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt
- [ ] T029 [US1] Add minimal logging (timestamp + email) for successful applications in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt
- [ ] T030 [US1] Implement @EventListener for UserCreatedEvent in UserMappingService in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt
- [ ] T031 [US1] Mark event listener as @Async for non-blocking execution in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt

### Backend: User Service Integration

- [ ] T032 [US1] Modify UserService: Publish UserCreatedEvent after user creation in src/backendng/src/main/kotlin/com/secman/service/UserService.kt
- [ ] T033 [US1] Verify event published for manual user creation in src/backendng/src/main/kotlin/com/secman/service/UserService.kt
- [ ] T034 [US1] Verify event published for OAuth auto-provisioned users in src/backendng/src/main/kotlin/com/secman/service/UserService.kt

### Frontend: Current Mappings Tab

- [ ] T035 [P] [US1] Create tab state management (useState) in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T036 [P] [US1] Add Bootstrap nav-tabs component structure in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T037 [US1] Implement "Current Mappings" tab UI with user existence indicator in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T038 [US1] Add "User Exists" badge column to Current Mappings table in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T039 [US1] Implement pagination for Current Mappings tab in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T040 [US1] Add lazy loading for tab data (fetch only when tab clicked) in src/frontend/src/components/UserMappingManagement.tsx

### Frontend: API Integration

- [ ] T041 [US1] Create fetchCurrentMappings(page, size) API call in src/frontend/src/services/userMappingService.ts
- [ ] T042 [US1] Integrate fetchCurrentMappings with "Current Mappings" tab in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T043 [US1] Add error handling for API calls in src/frontend/src/components/UserMappingManagement.tsx

### Backend: Controller Updates (if needed)

- [ ] T044 [US1] Add GET /api/user-mappings endpoint (current mappings, paginated) in src/backendng/src/main/kotlin/com/secman/controller/UserMappingController.kt
- [ ] T045 [US1] Add @Secured("ADMIN") annotation to new endpoint in src/backendng/src/main/kotlin/com/secman/controller/UserMappingController.kt
- [ ] T046 [US1] Verify existing import endpoints still work with future users in src/backendng/src/main/kotlin/com/secman/controller/ImportController.kt

**Checkpoint**: At this point, User Story 1 should be fully functional - administrators can upload future user mappings, users can be created, and mappings are automatically applied

---

## Phase 4: User Story 2 - Update/Override Future User Mappings (Priority: P2)

**Goal**: Allow administrators to correct mapping files before users are created, ensuring the correct mapping is applied when the user account is eventually provisioned

**Independent Test**: Upload a mapping for a future user, then upload a corrected mapping with different values, create the user, and verify the latest mapping is applied

### Backend: Update Logic

- [ ] T047 [US2] Verify ImportService handles duplicate email updates for future users in src/backendng/src/main/kotlin/com/secman/service/ImportService.kt
- [ ] T048 [US2] Verify last-in-file-wins behavior for future user mappings in src/backendng/src/main/kotlin/com/secman/service/ImportService.kt
- [ ] T049 [US2] Verify updated mapping (not original) is applied when user is created in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt

### Frontend: Update Indication

- [ ] T050 [US2] Add "Last Updated" timestamp display in Current Mappings tab in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T051 [US2] Add visual indication for recently updated mappings (optional) in src/frontend/src/components/UserMappingManagement.tsx

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently - administrators can upload and update future user mappings

---

## Phase 5: User Story 3 - Delete Future User Mappings (Priority: P3)

**Goal**: Allow administrators to remove mappings for cancelled hires so they don't accidentally get applied to different users

**Independent Test**: Create a future user mapping, delete it via the management interface, then create a user with that email and verify no mapping is applied

### Backend: Delete Functionality

- [ ] T052 [US3] Add DELETE /api/user-mappings/{id} endpoint in src/backendng/src/main/kotlin/com/secman/controller/UserMappingController.kt
- [ ] T053 [US3] Add @Secured("ADMIN") annotation to delete endpoint in src/backendng/src/main/kotlin/com/secman/controller/UserMappingController.kt
- [ ] T054 [US3] Implement delete logic in UserMappingService in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt
- [ ] T055 [US3] Verify deletion prevents mapping application when user is later created in src/backendng/src/main/kotlin/com/secman/service/UserMappingService.kt

### Frontend: Delete UI

- [ ] T056 [US3] Add delete button to Current Mappings table rows in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T057 [US3] Add confirmation dialog for delete action in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T058 [US3] Implement deleteUserMapping(id) API call in src/frontend/src/services/userMappingService.ts
- [ ] T059 [US3] Refresh table after successful deletion in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T060 [US3] Add error handling for delete failures in src/frontend/src/components/UserMappingManagement.tsx

**Checkpoint**: All three user stories should now be independently functional - full CRUD for future user mappings

---

## Phase 6: Applied History Tab (Cross-Story Enhancement)

**Goal**: Provide visibility into which future user mappings have been applied (audit trail)

**Purpose**: Supports all user stories by showing historical application of mappings

### Frontend: Applied History Tab

- [ ] T061 [P] Implement "Applied History" tab UI in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T062 [P] Add "Applied At" timestamp column in Applied History table in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T063 Implement pagination for Applied History tab in src/frontend/src/components/UserMappingManagement.tsx
- [ ] T064 Add sorting by appliedAt (newest first) in Applied History tab in src/frontend/src/components/UserMappingManagement.tsx

### Frontend: API Integration

- [ ] T065 Create fetchAppliedHistory(page, size) API call in src/frontend/src/services/userMappingService.ts
- [ ] T066 Integrate fetchAppliedHistory with "Applied History" tab in src/frontend/src/components/UserMappingManagement.tsx

### Backend: Controller Endpoint

- [ ] T067 Add GET /api/user-mappings/history endpoint (applied mappings, paginated) in src/backendng/src/main/kotlin/com/secman/controller/UserMappingController.kt
- [ ] T068 Add @Secured("ADMIN") annotation to history endpoint in src/backendng/src/main/kotlin/com/secman/controller/UserMappingController.kt

**Checkpoint**: Applied History tab functional - administrators can audit which mappings were applied and when

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

### Performance Optimization

- [ ] T069 [P] Verify database indexes on email (unique) and applied_at perform well with 10,000+ mappings
- [ ] T070 [P] Test pagination performance with large datasets (10,000+ records)
- [ ] T071 Verify mapping application completes within 2 seconds (NFR-001)

### Security Hardening

- [ ] T072 [P] Security review: Verify RBAC enforced on all new endpoints (@Secured("ADMIN"))
- [ ] T073 [P] Security review: Verify minimal logging (no sensitive data exposed)
- [ ] T074 Security review: Verify case-insensitive email matching prevents bypass

### Documentation

- [ ] T075 [P] Update OpenAPI documentation with new endpoints in contracts/user-mapping-api.yaml
- [ ] T076 [P] Update CLAUDE.md with new UserMapping entity fields and methods
- [ ] T077 Add inline documentation to UserMappingService methods
- [ ] T078 Add inline documentation to modified ImportService methods

### Code Quality

- [ ] T079 [P] Code review: Verify error handling consistency across all services
- [ ] T080 [P] Code review: Verify logging consistency (use SLF4J, INFO/WARN levels)
- [ ] T081 Refactor duplicate code (if any) between Excel and CSV import handlers

### Validation

- [ ] T082 Run quickstart.md validation: Upload future user mapping → Create user → Verify mapping applied
- [ ] T083 Run quickstart.md validation: Upload mixed file → Verify both existing and future users handled
- [ ] T084 Run quickstart.md validation: Test tab switching between Current and Applied History
- [ ] T085 Verify all edge cases from spec.md are handled correctly

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - User Story 1 (P1): Can start after Foundational - No dependencies on other stories
  - User Story 2 (P2): Can start after Foundational - Independent of US1 (though builds on same import logic)
  - User Story 3 (P3): Can start after Foundational - Independent of US1/US2
- **Applied History (Phase 6)**: Can start anytime after Foundational - Complements all user stories
- **Polish (Phase 7)**: Depends on all desired user stories being complete

### Within Each User Story

- Backend entity/repository changes before service implementation
- Service implementation before controller endpoints
- Controller endpoints before frontend integration
- Frontend API calls before UI components that use them
- Core functionality before polish/optimization

### Parallel Opportunities

**Phase 1 (Setup)**: Tasks T003, T004, T005 can run in parallel

**Phase 2 (Foundational)**: Tasks T013-T016 can run in parallel after T012 completes

**Phase 3 (User Story 1)**:
- T021-T025 can run in parallel (all modify ImportService)
- T035, T036 can run in parallel (UI structure)
- T041, T042, T043 are sequential

**Phase 6 (Applied History)**: Tasks T061, T062 can run in parallel

**Phase 7 (Polish)**: Tasks T069, T070, T072, T073, T075, T076, T079, T080 can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch all UserMappingRepository extensions together after entity is modified:
Task: "Add findByAppliedAtIsNull(pageable: Pageable) method" (T013)
Task: "Add findByAppliedAtIsNotNull(pageable: Pageable) method" (T014)
Task: "Add countByAppliedAtIsNull() method" (T015)
Task: "Add countByAppliedAtIsNotNull() method" (T016)

# Launch all ImportService modifications together:
Task: "Remove user existence check" (T021)
Task: "Update Excel upload logic" (T022)
Task: "Update CSV upload logic" (T023)
Task: "Ensure duplicate email handling works" (T024)
Task: "Verify existing validation rules maintained" (T025)

# Launch frontend UI structure tasks together:
Task: "Create tab state management" (T035)
Task: "Add Bootstrap nav-tabs component" (T036)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (verify existing structure)
2. Complete Phase 2: Foundational (entity changes, repository, event infrastructure) - CRITICAL
3. Complete Phase 3: User Story 1 (upload future users, automatic application)
4. **STOP and VALIDATE**: Test User Story 1 independently
   - Upload mapping for future user
   - Create user manually
   - Verify mapping applied
   - Verify asset access granted immediately
5. Deploy/demo if ready - **This is a complete MVP!**

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP! Core value delivered)
3. Add User Story 2 → Test independently → Deploy/Demo (Update capability added)
4. Add User Story 3 → Test independently → Deploy/Demo (Delete capability added)
5. Add Applied History (Phase 6) → Deploy/Demo (Audit trail added)
6. Add Polish (Phase 7) → Final release

Each phase adds value without breaking previous phases.

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together (critical path)
2. Once Foundational is done:
   - **Developer A**: User Story 1 (T021-T046) - Priority focus
   - **Developer B**: User Story 2 (T047-T051) - Can start in parallel
   - **Developer C**: User Story 3 (T052-T060) - Can start in parallel
   - **Developer D**: Applied History (T061-T068) - Can start in parallel
3. Stories complete and integrate independently
4. Team converges on Phase 7 (Polish)

---

## Notes

- [P] tasks = different files, no dependencies within marked group
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Tests excluded per user request - will be written during implementation per TDD
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence

---

## Task Count Summary

- **Phase 1 (Setup)**: 5 tasks
- **Phase 2 (Foundational)**: 15 tasks (BLOCKING)
- **Phase 3 (User Story 1)**: 26 tasks (MVP)
- **Phase 4 (User Story 2)**: 5 tasks
- **Phase 5 (User Story 3)**: 9 tasks
- **Phase 6 (Applied History)**: 8 tasks
- **Phase 7 (Polish)**: 17 tasks

**Total**: 85 implementation tasks (test tasks excluded per user request)

**Parallel Opportunities**: 20+ tasks can run in parallel at various stages

**MVP Scope**: Phases 1, 2, and 3 (46 tasks) deliver complete core functionality
