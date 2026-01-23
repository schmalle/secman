# Tasks: Requirement ID.Revision Versioning

**Input**: Design documents from `/specs/066-requirement-versioning/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Not requested in feature specification. Tasks focus on implementation only.

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

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Database migration and foundational entity setup

- [x] T001 Create Flyway migration V066__add_requirement_internal_id.sql in src/backendng/src/main/resources/db/migration/V066__add_requirement_internal_id.sql
- [x] T002 [P] Create RequirementIdSequence entity in src/backendng/src/main/kotlin/com/secman/domain/RequirementIdSequence.kt
- [x] T003 [P] Create RequirementIdSequenceRepository in src/backendng/src/main/kotlin/com/secman/repository/RequirementIdSequenceRepository.kt

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core ID generation service and entity modifications that ALL user stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Add internalId field to Requirement entity in src/backendng/src/main/kotlin/com/secman/domain/Requirement.kt
- [x] T005 Add idRevision computed property to Requirement entity in src/backendng/src/main/kotlin/com/secman/domain/Requirement.kt
- [x] T006 [P] Add internalId and revision fields to RequirementSnapshot entity in src/backendng/src/main/kotlin/com/secman/domain/RequirementSnapshot.kt
- [x] T007 [P] Add idRevision computed property to RequirementSnapshot in src/backendng/src/main/kotlin/com/secman/domain/RequirementSnapshot.kt
- [x] T008 Update RequirementSnapshot.fromRequirement() to capture internalId and versionNumber in src/backendng/src/main/kotlin/com/secman/domain/RequirementSnapshot.kt
- [x] T009 Create RequirementIdService with getNextId() method using SELECT FOR UPDATE in src/backendng/src/main/kotlin/com/secman/service/RequirementIdService.kt
- [x] T010 Add formatId() helper function to format IDs as REQ-NNN in src/backendng/src/main/kotlin/com/secman/service/RequirementIdService.kt

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Assign and Display Requirement IDs (Priority: P1) 🎯 MVP

**Goal**: Each requirement has a unique internal ID (REQ-001, REQ-002, ...) that never changes

**Independent Test**: Create a new requirement and verify it receives a unique ID. Edit the requirement and verify ID remains unchanged.

### Implementation for User Story 1

- [x] T011 [US1] Update RequirementService.createRequirement() to call RequirementIdService.getNextId() and assign internalId in src/backendng/src/main/kotlin/com/secman/service/RequirementService.kt
- [x] T012 [US1] Update RequirementResponse DTO to include internalId, revision (versionNumber), and idRevision fields in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt
- [x] T013 [US1] Update RequirementResponse.from() to map internalId and versionNumber from Requirement in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt
- [x] T014 [P] [US1] Update RequirementManagement.tsx to display internalId badge in requirement list in src/frontend/src/components/RequirementManagement.tsx
- [x] T015 [P] [US1] Update RequirementManagement.tsx to display internalId in requirement detail view in src/frontend/src/components/RequirementManagement.tsx
- [x] T016 [US1] Verify internalId is NOT editable in RequirementManagement.tsx edit form in src/frontend/src/components/RequirementManagement.tsx

**Checkpoint**: User Story 1 complete - requirements have unique IDs that display correctly

---

## Phase 4: User Story 2 - Track Requirement Revisions (Priority: P1)

**Goal**: Revision number increments on content changes, displayed as ID.Revision (e.g., REQ-001.3)

**Independent Test**: Create a requirement, edit content fields multiple times, verify revision increments each time. Edit relationship-only, verify revision does NOT increment.

### Implementation for User Story 2

- [x] T017 [US2] Add shouldIncrementRevision() helper to detect content changes in src/backendng/src/main/kotlin/com/secman/service/RequirementService.kt
- [x] T018 [US2] Update RequirementService.updateRequirement() to call incrementVersion() on content changes in src/backendng/src/main/kotlin/com/secman/service/RequirementService.kt
- [x] T019 [US2] Ensure relationship-only changes (usecases, norms) do NOT increment revision in src/backendng/src/main/kotlin/com/secman/service/RequirementService.kt
- [x] T020 [P] [US2] Update RequirementManagement.tsx to display full ID.Revision format (e.g., REQ-001.3) in src/frontend/src/components/RequirementManagement.tsx
- [x] T021 [P] [US2] Add tooltip showing updatedAt timestamp when hovering over ID.Revision badge in src/frontend/src/components/RequirementManagement.tsx

**Checkpoint**: User Story 2 complete - revisions track content changes correctly

---

## Phase 5: User Story 3 - Export ID.Revision in Excel and Word (Priority: P2)

**Goal**: Excel and Word exports include ID.Revision column/header for all requirements

**Independent Test**: Export requirements to Excel and Word, verify ID.Revision appears as first column/header prefix.

### Implementation for User Story 3

- [x] T022 [US3] Update createExcelWorkbook() to add ID.Revision as first column header in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt
- [x] T023 [US3] Update createExcelWorkbook() to populate ID.Revision column with requirement.idRevision in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt
- [x] T024 [US3] Update createWordDocument() to use ID.Revision in requirement headers instead of "Req N" in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt
- [x] T025 [US3] Update translated export methods to include ID.Revision in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt
- [x] T026 [US3] Update snapshotToRequirement() to set internalId and versionNumber from snapshot for release exports in src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt

**Checkpoint**: User Story 3 complete - exports include ID.Revision

---

## Phase 6: User Story 4 - Create and Manage Releases with ID.Revision (Priority: P2)

**Goal**: Release snapshots capture and preserve ID.Revision at release time

**Independent Test**: Create a release, modify requirements, verify release snapshot still shows original ID.Revision values.

### Implementation for User Story 4

- [x] T027 [US4] Verify ReleaseService.createRelease() uses updated RequirementSnapshot.fromRequirement() with internalId/revision in src/backendng/src/main/kotlin/com/secman/service/ReleaseService.kt
- [x] T028 [P] [US4] Update ReleaseManagement.tsx to display ID.Revision for requirements in release view in src/frontend/src/components/ReleaseManagement.tsx
- [x] T029 [P] [US4] Update RequirementSnapshotSummary DTO to include internalId, revision, idRevision in src/backendng/src/main/kotlin/com/secman/dto/ComparisonResult.kt

**Checkpoint**: User Story 4 complete - releases capture ID.Revision snapshots

---

## Phase 7: User Story 5 - Compare Releases and Export Diff Table (Priority: P3)

**Goal**: Release comparison shows revision changes and diff export includes ID, old revision, new revision columns

**Independent Test**: Create two releases with modified requirements, compare them, verify old/new revisions shown. Export diff and verify columns.

### Implementation for User Story 5

- [x] T030 [US5] Update RequirementDiff DTO to include internalId, oldRevision, newRevision in src/backendng/src/main/kotlin/com/secman/dto/ComparisonResult.kt
- [x] T031 [US5] Update RequirementComparisonService.compare() to populate revision fields in RequirementDiff in src/backendng/src/main/kotlin/com/secman/service/RequirementComparisonService.kt
- [x] T032 [P] [US5] Update ReleaseComparison.tsx to display old/new revisions in modified requirements section in src/frontend/src/components/ReleaseComparison.tsx
- [x] T033 [P] [US5] Update comparisonExport.ts to add ID.Revision columns (ID, Old Rev, New Rev) in src/frontend/src/utils/comparisonExport.ts
- [x] T034 [US5] Update RequirementSnapshotSummary in added/deleted sections to show idRevision in src/frontend/src/components/ReleaseComparison.tsx

**Checkpoint**: User Story 5 complete - release comparison shows revision changes with export

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and cleanup

- [ ] T035 Run Flyway migration and verify existing requirements have IDs assigned in database ID order
- [x] T036 Verify ./gradlew build passes with all changes
- [x] T037 [P] Update API documentation with new response fields in contracts/api-changes.yaml
- [ ] T038 Run quickstart.md validation checklist manually

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup (T001-T003) completion - BLOCKS all user stories
- **User Stories (Phases 3-7)**: All depend on Foundational phase completion
  - US1 and US2 are both P1 priority but can run in parallel
  - US3 and US4 are P2 and can run after US1/US2 or in parallel
  - US5 is P3 and can run last or in parallel with others
- **Polish (Phase 8)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Depends on Foundational - No dependencies on other stories
- **User Story 2 (P1)**: Depends on Foundational - No dependencies on other stories (shares display with US1)
- **User Story 3 (P2)**: Depends on Foundational - Uses idRevision from US1/US2 but independently testable
- **User Story 4 (P2)**: Depends on Foundational - Extends snapshot behavior from setup
- **User Story 5 (P3)**: Depends on Foundational - Uses snapshot data from US4 context

### Within Each User Story

- Backend changes before frontend changes
- Service layer before controller/DTO changes
- Core implementation before UI integration
- All [P] tasks within a story can run in parallel

### Parallel Opportunities

- T002, T003 can run in parallel (Setup phase)
- T004-T005, T006-T008 can run in parallel (different entities)
- T014, T015 can run in parallel (different UI views)
- T020, T021 can run in parallel (different UI features)
- T028, T029 can run in parallel (different files)
- T032, T033 can run in parallel (different files)

---

## Parallel Example: User Story 1

```bash
# Launch backend tasks first:
Task T011: "Update RequirementService to assign internalId"
Task T012-T013: "Update RequirementResponse DTO"

# Then launch frontend tasks in parallel:
Task T014: "Display internalId in requirement list"
Task T015: "Display internalId in detail view"
```

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2)

1. Complete Phase 1: Setup (migration + entities)
2. Complete Phase 2: Foundational (ID service + entity fields)
3. Complete Phase 3: User Story 1 (ID assignment + display)
4. Complete Phase 4: User Story 2 (revision tracking)
5. **STOP and VALIDATE**: Create requirements, edit them, verify ID.Revision works
6. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add US1 + US2 → ID.Revision displays correctly (MVP!)
3. Add US3 → Exports include ID.Revision
4. Add US4 → Releases capture snapshots
5. Add US5 → Release comparison shows revisions
6. Each story adds value without breaking previous stories

### Single Developer Strategy

Execute phases sequentially:
1. Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6 → Phase 7 → Phase 8

---

## Notes

- [P] tasks = different files, no dependencies within phase
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable after Foundational phase
- Flyway migration handles data backfill for existing requirements
- Revision uses existing versionNumber field from VersionedEntity (no new column needed)
- Commit after each task or logical group
