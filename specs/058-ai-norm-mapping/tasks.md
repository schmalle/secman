# Tasks: AI-Powered Norm Mapping for Requirements

**Input**: Design documents from `/specs/058-ai-norm-mapping/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Not requested in specification - test tasks omitted per constitution.

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story (US1, US2, US3, US4)
- Paths relative to repository root

---

## Phase 1: Setup

**Purpose**: Verify existing infrastructure supports this feature

- [ ] T001 Verify TranslationConfig entity has active config with OpenRouter API key
- [ ] T002 [P] Verify RequirementRepository and NormRepository are accessible

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared DTOs and service infrastructure needed by all user stories

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T003 Create NormMappingDto.kt with all DTOs in src/backendng/src/main/kotlin/com/secman/dto/NormMappingDto.kt
  - NormMappingSuggestionRequest
  - NormMappingSuggestionResponse
  - RequirementSuggestions
  - NormSuggestion
  - ApplyMappingsRequest
  - NormToApply
  - ApplyMappingsResponse
- [ ] T004 Create NormMappingService.kt skeleton in src/backendng/src/main/kotlin/com/secman/service/NormMappingService.kt
- [ ] T005 Create NormMappingController.kt skeleton with @Secured annotations in src/backendng/src/main/kotlin/com/secman/controller/NormMappingController.kt
- [ ] T006 [P] Create normMappingService.ts API client in src/frontend/src/services/normMappingService.ts

**Checkpoint**: Foundation ready - DTOs, service skeleton, and API client in place

---

## Phase 3: User Story 1 - Auto-Map Requirements to Security Standards (Priority: P1) 🎯 MVP

**Goal**: Enable clicking "Missing mapping" button to query AI and receive norm suggestions

**Independent Test**: Click "Missing mapping" button, verify AI suggestions are returned with requirement text, standards, controls, and confidence levels

### Implementation for User Story 1

- [ ] T007 [US1] Implement getUnmappedRequirements() method in NormMappingService.kt to query requirements without norms
- [ ] T008 [US1] Implement buildAIPrompt() method in NormMappingService.kt for batch requirement analysis prompt
- [ ] T009 [US1] Implement callOpenRouter() method in NormMappingService.kt using TranslationConfig and Opus 4.5 model (`anthropic/claude-opus-4-5-20251101`)
- [ ] T010 [US1] Implement parseAIResponse() method in NormMappingService.kt to convert JSON to NormSuggestion DTOs
- [ ] T011 [US1] Implement suggestMappings() orchestration method in NormMappingService.kt
- [ ] T012 [US1] Implement POST /api/norm-mapping/suggest endpoint in NormMappingController.kt
- [ ] T013 [US1] Implement suggestMappings() function in normMappingService.ts frontend API client
- [ ] T014 [US1] Re-enable "Missing mapping" button in RequirementManagement.tsx (remove disabled={true})
- [ ] T015 [US1] Implement handleMissingMappings() click handler in RequirementManagement.tsx to call API

**Checkpoint**: User Story 1 complete - AI suggestions returned when clicking "Missing mapping" button

---

## Phase 4: User Story 2 - Review and Select AI Suggestions (Priority: P1)

**Goal**: Display suggestions in modal with checkboxes, allow selection, save approved mappings

**Independent Test**: View modal with suggestions, select subset via checkboxes, click apply, verify only selected mappings saved

### Implementation for User Story 2

- [ ] T016 [US2] Implement findOrCreateNorm() method in NormMappingService.kt for auto-creating norms
- [ ] T017 [US2] Implement applyMappings() method in NormMappingService.kt to save selected mappings to requirements
- [ ] T018 [US2] Implement POST /api/norm-mapping/apply endpoint in NormMappingController.kt
- [ ] T019 [US2] Implement applyMappings() function in normMappingService.ts frontend API client
- [ ] T020 [US2] Update MappingSuggestionsModal component in RequirementManagement.tsx to display AI suggestions with checkboxes
- [ ] T021 [US2] Implement checkbox selection state management in RequirementManagement.tsx
- [ ] T022 [US2] Implement handleApplyMappings() to call apply API with selected suggestions in RequirementManagement.tsx
- [ ] T023 [US2] Add confidence level badges (color-coded) to suggestion display in RequirementManagement.tsx
- [ ] T024 [US2] Pre-select checkboxes for suggestions with confidence >= 4 in RequirementManagement.tsx

**Checkpoint**: User Stories 1 AND 2 complete - Full suggest → review → apply flow works

---

## Phase 5: User Story 3 - Progress Feedback During AI Processing (Priority: P2)

**Goal**: Show loading indicator and status message during AI analysis

**Independent Test**: Click "Missing mapping", observe spinner and "Analyzing..." text, verify disabled state

### Implementation for User Story 3

- [ ] T025 [US3] Add isMappingInProgress state to RequirementManagement.tsx (already exists, verify working)
- [ ] T026 [US3] Update button to show spinner and "Analyzing..." text when isMappingInProgress in RequirementManagement.tsx
- [ ] T027 [US3] Disable button during processing to prevent duplicate clicks in RequirementManagement.tsx
- [ ] T028 [US3] Add error state handling to display user-friendly error messages in RequirementManagement.tsx

**Checkpoint**: User Story 3 complete - Loading feedback works during AI processing

---

## Phase 6: User Story 4 - Skip Already-Mapped Requirements (Priority: P2)

**Goal**: Only process requirements without existing norm mappings

**Independent Test**: Mix of mapped/unmapped requirements, run AI analysis, verify only unmapped appear in suggestions

### Implementation for User Story 4

- [ ] T029 [US4] Implement GET /api/norm-mapping/unmapped-count endpoint in NormMappingController.kt
- [ ] T030 [US4] Implement getUnmappedCount() in normMappingService.ts frontend API client
- [ ] T031 [US4] Add "all requirements mapped" message when count is 0 in RequirementManagement.tsx
- [ ] T032 [US4] Update missing norm count display to use API instead of local calculation in RequirementManagement.tsx

**Checkpoint**: All user stories complete - Full feature functional

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Final improvements and validation

- [ ] T033 [P] Add logging for AI mapping operations in NormMappingService.kt
- [ ] T034 [P] Add error handling for OpenRouter API failures (timeout, 503, invalid response) in NormMappingService.kt
- [ ] T035 [P] Handle edge case: requirement text truncation for very long texts in NormMappingService.kt
- [ ] T036 Verify RBAC enforcement (ADMIN, REQ, SECCHAMPION only) via manual test
- [ ] T037 Run quickstart.md validation - test full user flow
- [ ] T038 Verify build passes with ./gradlew build

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - verification only
- **Foundational (Phase 2)**: Depends on Setup - BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational completion
- **User Story 2 (Phase 4)**: Depends on User Story 1 (needs suggestions to display)
- **User Story 3 (Phase 5)**: Can start after Foundational (parallel with US1/US2 frontend work)
- **User Story 4 (Phase 6)**: Can start after Foundational (parallel with other stories)
- **Polish (Phase 7)**: Depends on all user stories complete

### User Story Dependencies

```
Phase 2 (Foundational)
    │
    ├──> US1 (Core AI query) ──> US2 (Modal & Apply)
    │                                    │
    ├──> US3 (Progress feedback) ────────┤
    │                                    │
    └──> US4 (Skip mapped) ──────────────┴──> Phase 7 (Polish)
```

### Parallel Opportunities

**Phase 2 (Foundational)**:
- T003-T005 sequential (service depends on DTOs)
- T006 parallel with T003-T005 (frontend independent)

**Phase 3-4 (US1-US2)**:
- Backend tasks (T007-T012, T016-T018) must complete before frontend integration
- Frontend API client tasks can parallel with backend

**Phase 5-6 (US3-US4)**:
- Can start in parallel with US1-US2 for non-overlapping files
- T029-T030 (US4 backend/API) parallel with T025-T028 (US3 frontend)

---

## Parallel Example: Phase 2 Foundational

```bash
# Sequential backend tasks:
Task: "Create NormMappingDto.kt" (T003)
Task: "Create NormMappingService.kt skeleton" (T004) - after T003
Task: "Create NormMappingController.kt skeleton" (T005) - after T004

# Parallel frontend task:
Task: "Create normMappingService.ts API client" (T006) - parallel with above
```

## Parallel Example: User Story 1 & 2 Backend

```bash
# US1 Backend (sequential):
Task: "Implement getUnmappedRequirements()" (T007)
Task: "Implement buildAIPrompt()" (T008)
Task: "Implement callOpenRouter()" (T009)
Task: "Implement parseAIResponse()" (T010)
Task: "Implement suggestMappings()" (T011)
Task: "Implement POST /suggest endpoint" (T012)

# US2 Backend (after US1 backend complete):
Task: "Implement findOrCreateNorm()" (T016)
Task: "Implement applyMappings()" (T017)
Task: "Implement POST /apply endpoint" (T018)
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2)

1. Complete Phase 1: Setup verification
2. Complete Phase 2: Foundational DTOs and skeletons
3. Complete Phase 3: User Story 1 - AI suggestions work
4. Complete Phase 4: User Story 2 - Apply mappings works
5. **STOP and VALIDATE**: Test full suggest → apply flow
6. Deploy/demo if ready - core value delivered!

### Incremental Delivery

1. Setup + Foundational → Infrastructure ready
2. Add US1 → Test AI suggestions → Demo (suggestions work!)
3. Add US2 → Test apply flow → Demo (MVP complete!)
4. Add US3 → Test loading UX → Demo (polish)
5. Add US4 → Test filtering → Demo (optimization)
6. Polish → Final validation

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [Story] label maps task to specific user story
- US1 and US2 together form the MVP (P1 priority)
- US3 and US4 are enhancements (P2 priority)
- Existing button and modal code in RequirementManagement.tsx can be adapted
- TranslationService.kt provides pattern for OpenRouter HTTP calls
- Commit after each task or logical group
