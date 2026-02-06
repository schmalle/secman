# Tasks: Release Rework

**Input**: Design documents from `/specs/078-release-rework/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/api-changes.md

**Tests**: E2E test suite is explicitly requested (FR-014, User Story 4). No unit tests per project principles.

**Organization**: US1 (Context Switching) and US2 (Status Model) are tightly coupled — the status enum rename must land before context switching can reference the new statuses. They share Phase 3. US3 (Export) and US4 (E2E Tests) are independent phases.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Phase 1: Setup

**Purpose**: Branch and workspace preparation

- [x] T001 Create feature branch `078-release-rework` from `main` (if not already created)

---

## Phase 2: Foundational — Status Enum Rename + Migration (Blocking Prerequisites)

**Purpose**: Rename the ReleaseStatus enum values and create the Flyway migration. ALL subsequent phases depend on this.

**Why blocking**: Every backend service, MCP tool, and frontend component references the old status names. The enum rename is the atomic change that everything else builds on.

- [x] T002 [US2] Rename `ReleaseStatus` enum values in `src/backendng/src/main/kotlin/com/secman/domain/Release.kt`: DRAFT→PREPARATION, IN_REVIEW→ALIGNMENT, ACTIVE→ACTIVE (no change), remove LEGACY and PUBLISHED, add ARCHIVED
- [x] T003 [US2] Create Flyway migration `src/backendng/src/main/resources/db/migration/V078__release_status_rework.sql` with three UPDATE statements: DRAFT→PREPARATION, IN_REVIEW→ALIGNMENT, LEGACY/PUBLISHED→ARCHIVED
- [x] T004 [US2] Update `src/backendng/src/main/kotlin/com/secman/service/ReleaseService.kt` — replace all references to old status names (DRAFT, IN_REVIEW, LEGACY, PUBLISHED) with new names (PREPARATION, ALIGNMENT, ARCHIVED). Update transition validation logic per data-model.md state machine: PREPARATION→ALIGNMENT, PREPARATION→ACTIVE (direct activation), ALIGNMENT→ACTIVE, ALIGNMENT→PREPARATION, ACTIVE→ARCHIVED (automatic only).
- [x] T005 [US2] Update `src/backendng/src/main/kotlin/com/secman/service/AlignmentService.kt` — change `startAlignment()` from DRAFT→IN_REVIEW to PREPARATION→ALIGNMENT, `finalizeAlignment()` from IN_REVIEW→ACTIVE to ALIGNMENT→ACTIVE, `cancelAlignment()` from IN_REVIEW→DRAFT to ALIGNMENT→PREPARATION (per research.md R5)
- [x] T006 [P] [US2] Update MCP tool `src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateReleaseTool.kt` — update status text references from DRAFT to PREPARATION
- [x] T007 [P] [US2] Update MCP tool `src/backendng/src/main/kotlin/com/secman/mcp/tools/SetReleaseStatusTool.kt` — update validation text and allowed transition descriptions
- [x] T008 [P] [US2] Update MCP tool `src/backendng/src/main/kotlin/com/secman/mcp/tools/ListReleasesTool.kt` — update filter validation to accept PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED
- [x] T009 [US2] Run `./gradlew build` — verify backend compiles with zero errors after all status renames

**Checkpoint**: Backend compiles cleanly with new status model. No frontend changes yet — frontend will break until Phase 3.

---

## Phase 3: US1 + US2 — Context Switching + Status Model UI (Priority: P1)

**Goal**: Users see requirements through a release lens by default (ACTIVE release). Release selector persists selection in sessionStorage. UI reflects the four new statuses with correct badge styling. Editing is blocked for ARCHIVED/ACTIVE releases.

**Independent Test**: Create two releases with different requirement snapshots, switch between them in the UI, verify displayed requirements match each release's snapshot.

### Frontend — TypeScript Types

- [x] T010 [US2] Update `src/frontend/src/services/releaseService.ts` — replace ReleaseStatus type definition (line 17): remove DRAFT, IN_REVIEW, LEGACY, PUBLISHED; set to `'PREPARATION' | 'ALIGNMENT' | 'ACTIVE' | 'ARCHIVED'`. Also update the duplicate type in `src/frontend/src/components/ReleaseManagement.tsx` (line 9) to match. Update any status-related helper functions and JSDoc comments referencing old status names.

### Frontend — Release Management & Status Display

- [x] T011 [P] [US2] Update `src/frontend/src/components/ReleaseManagement.tsx` — replace all old status string literals with new names. Update badge CSS classes (PREPARATION=info, ALIGNMENT=warning, ACTIVE=success, ARCHIVED=secondary). Update CRUD logic status references.
- [x] T012 [P] [US2] Update `src/frontend/src/components/ReleaseStatusActions.tsx` — update transition button labels and modal text: "Start Alignment" (PREPARATION→ALIGNMENT), "Cancel Alignment" (ALIGNMENT→PREPARATION), "Activate Release" (PREPARATION/ALIGNMENT→ACTIVE). Remove any LEGACY/PUBLISHED-specific actions.
- [x] T013 [P] [US2] Update `src/frontend/src/components/ReleaseList.tsx` — update status badge rendering to use new status names and corresponding Bootstrap badge classes.
- [x] T013a [P] [US2] Update `src/frontend/src/components/ReleaseCreateModal.tsx` — change "DRAFT" status text (line 267) to "PREPARATION".
- [x] T013b [P] [US2] Update `src/frontend/src/components/ReleaseDetail.tsx` — update status switch cases: DRAFT→PREPARATION, PUBLISHED→ARCHIVED (lines 301, 303). Update any badge classes.
- [x] T013c [P] [US2] Update `src/frontend/src/components/AlignmentDashboard.tsx` — change "DRAFT status" text (line 104) to "PREPARATION status".

### Frontend — Context Switching (Core US1 Behavior)

- [x] T014 [US1] Update `src/frontend/src/components/ReleaseSelector.tsx` — on page load, fetch releases and default to the ACTIVE release's ID. Store selection in sessionStorage under key `secman_selectedReleaseId`. When no ACTIVE release exists, fall back to null (live) with visible indicator. Restore selection from sessionStorage on remount. Clear `secman_selectedReleaseId` from sessionStorage on logout.
- [x] T015 [US1] Update `src/frontend/src/components/RequirementManagement.tsx` — on mount, read `secman_selectedReleaseId` from sessionStorage and set as initial `selectedReleaseId`. Default to ACTIVE release ID if no sessionStorage value. Implement edit guard: hide/disable editing controls when viewing ARCHIVED or ACTIVE release (FR-016). Allow editing for PREPARATION and ALIGNMENT releases.

### Frontend Build Verification

- [x] T016 [US1+US2] Run `cd src/frontend && npm run build` — verify frontend compiles with zero errors after all status and context switching changes
- [x] T017 [US1+US2] Run `./gradlew build` — full build verification (backend + frontend) for Phase 3 completion

**Checkpoint**: Users can switch between releases, see correct snapshots, and the four-status model is reflected consistently in the UI. Editing blocked for ARCHIVED/ACTIVE. Session persistence works.

---

## Phase 4: US3 — Release-Aware Requirement Export (Priority: P2)

**Goal**: Exports always use the selected release context. When a user exports from a release, the output contains exactly the snapshot data for that release.

**Independent Test**: Select two different releases, export from each, verify export files contain the correct requirement versions.

- [x] T018 [US3] Add `releaseId` query parameter support to use-case filtered export endpoints in `src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt`: `exportToDocxByUseCase` (line 433), `exportToExcelByUseCase` (line 503), `exportToDocxTranslatedByUseCase` (line 761), `exportToExcelTranslatedByUseCase` (line 1024). When `releaseId` is provided, filter snapshots by `usecaseIdsSnapshot` JSON field instead of querying live requirements. (Fixes research.md R4 gap for FR-012.)
- [x] T019 [US3] Update `src/frontend/src/components/Export.tsx` — always pass the currently selected `releaseId` (from sessionStorage or ReleaseSelector state) to all export API calls. Ensure Word, Excel, use-case filtered, and translated variants all append `?releaseId=` or `&releaseId=` to their endpoints.
- [x] T020 [US3] Run `cd src/frontend && npm run build` — verify Export component changes compile cleanly
- [x] T021 [US3] Run `./gradlew build` — full build verification for Phase 4

**Checkpoint**: Exports from any release produce files containing that release's snapshot data. All export formats respect the release context.

---

## Phase 5: US4 — E2E Test Suite (Priority: P2)

**Goal**: Automated shell script validates the complete release lifecycle end-to-end against a running secman instance.

**Independent Test**: Run `./tests/release-e2e-test.sh` against a running secman instance with env vars set.

- [x] T022 [US4] Create `tests/release-e2e-test.sh` following pattern from `tests/mcp-e2e-workgroup-test.sh`. Script must:
  - Require SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY env vars
  - Authenticate via JWT login
  - Use trap for cleanup on EXIT (delete test releases, requirements)
  - Create test requirements via `add_requirement` MCP tool
  - Create Release 1.0 via `create_release` → verify PREPARATION status
  - Activate Release 1.0 via `set_release_status` → verify ACTIVE status
  - Modify a requirement via `add_requirement` (update)
  - Create Release 2.0 via `create_release` → verify PREPARATION status
  - Verify `get_release` with `includeRequirements=true` returns correct snapshots per release
  - Compare releases via `compare_releases` to verify diffs
  - Export from each release and verify file download succeeds (HTTP 200)
  - Print pass/fail summary with test counts
- [x] T023 [US4] Make script executable: `chmod +x tests/release-e2e-test.sh`

**Checkpoint**: E2E test script runs end-to-end, validates all release lifecycle operations, and cleans up test data.

---

## Phase 6: Polish & Verification

**Purpose**: Final build, cross-cutting validation

- [x] T024 [P] Run `./gradlew build` — final full backend build
- [x] T025 [P] Run `cd src/frontend && npm run build` — final full frontend build
- [x] T026 Update documentation: `docs/MCP.md` — update status values in release tool descriptions (DRAFT→PREPARATION, IN_REVIEW→ALIGNMENT, LEGACY/PUBLISHED→ARCHIVED). Update `CLAUDE.md` — update release status list under Releases section. (Constitution VI compliance.)
- [x] T027 Run quickstart.md validation — verify all files listed in quickstart.md were modified and all verification commands pass

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — BLOCKS all subsequent phases
  - T002 must complete before T003-T008 (enum definition drives everything)
  - T004, T005 are sequential (both modify service layer, T004 first)
  - T006, T007, T008 can run in parallel (different MCP tool files)
  - T009 gates Phase 2 completion
- **Phase 3 (US1+US2 Frontend)**: Depends on Phase 2 completion
  - T010 must complete before T011-T015 (TypeScript types drive components)
  - T011, T012, T013, T013a, T013b, T013c can run in parallel (different component files)
  - T014 before T015 (ReleaseSelector drives RequirementManagement defaults)
  - T016, T017 gate Phase 3 completion
- **Phase 4 (US3 Export)**: Depends on Phase 3 completion (needs working context switching)
  - T018 (backend) before T019 (frontend) — new endpoints must exist before frontend calls them
  - T020, T021 gate Phase 4 completion
- **Phase 5 (US4 E2E)**: Depends on Phase 4 completion (tests validate all stories)
- **Phase 6 (Polish)**: Depends on all previous phases

### Parallel Opportunities

Within Phase 2: T006, T007, T008 can run in parallel (different MCP tool files)
Within Phase 3: T011, T012, T013, T013a, T013b, T013c can run in parallel (different component files)
Phase 6: T024, T025 can run in parallel (backend vs frontend builds)

---

## Implementation Strategy

### Recommended: Sequential by Phase

1. Complete Phase 2 (Foundational) → Backend compiles with new status model
2. Complete Phase 3 (US1+US2) → Full UI working with context switching + status display
3. Complete Phase 4 (US3) → Exports respect release context
4. Complete Phase 5 (US4) → E2E test validates everything
5. Complete Phase 6 (Polish) → Final verification

### Key Risk: Atomic Status Rename

The status enum rename (Phase 2) is an all-or-nothing change. The backend will not compile until all Kotlin files referencing old status values are updated. Frontend will not compile until all TypeScript/React files are updated (Phase 3). Plan for backend-first, then frontend in a single phase.

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- FR-016 clarification: Editing blocked for ARCHIVED/ACTIVE only; allowed for PREPARATION/ALIGNMENT
- No unit tests per project principles (CLAUDE.md: "Never write testcases")
- E2E test explicitly requested in spec (FR-014) — included as US4
- Commit after each completed phase checkpoint
