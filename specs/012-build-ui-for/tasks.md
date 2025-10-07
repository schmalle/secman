# Tasks: Release Management UI Enhancement

**Input**: Design documents from `/specs/012-build-ui-for/`
**Prerequisites**: plan.md ✅, spec.md ✅

**Tests**: E2E tests with Playwright are included as this is a TDD-driven feature per constitutional requirement.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions
- **Web app**: `src/frontend/src/` for components/pages, `src/frontend/tests/e2e/` for tests
- Frontend-only feature - no backend changes needed

---

## Phase 0: Foundation (Shared Infrastructure)

**Purpose**: Set up service layer, test infrastructure, and shared utilities

**⚠️ CRITICAL**: These tasks must be complete before user story implementation begins

- [ ] T001 [P] Create release service API wrapper in src/frontend/src/services/releaseService.ts
- [ ] T002 [P] Create Playwright test helpers in src/frontend/tests/e2e/helpers/releaseHelpers.ts
- [ ] T003 [P] Add exceljs dependency to src/frontend/package.json for client-side comparison export
- [ ] T004 Verify authenticatedFetch utility works with release endpoints (manual test)
- [ ] T005 Document backend endpoint status (comparison export missing, status update endpoint verification)

**Validation**: 
- releaseService methods compile without errors
- Test helpers can create/delete test releases
- exceljs imports successfully

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 1: User Story 1 - View and Browse Releases (Priority: P1) 🎯 MVP

**Goal**: Users can view all releases with filtering, search, and pagination

**Independent Test**: Navigate to /releases, see list with filters working

### Tests for User Story 1 (TDD - Write First) ⚠️

**NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T006 [P] [US1] E2E test: List displays all releases in src/frontend/tests/e2e/releases/release-list.spec.ts
- [ ] T007 [P] [US1] E2E test: Status filter works (ALL, DRAFT, PUBLISHED, ARCHIVED)
- [ ] T008 [P] [US1] E2E test: Search filters by version and name
- [ ] T009 [P] [US1] E2E test: Pagination navigates correctly
- [ ] T010 [P] [US1] E2E test: Empty state displays when no releases exist
- [ ] T011 [P] [US1] E2E test: Click release navigates to detail page

**Expected Result**: All 6 tests FAIL (feature not implemented yet)

### Implementation for User Story 1

- [ ] T012 [P] [US1] Create ReleaseList component in src/frontend/src/components/ReleaseList.tsx
  - State: releases, loading, error, filters (status, search), pagination (page, totalPages)
  - useEffect: Fetch releases on mount and when filters/pagination change
  - Render: Release cards/table with badges, filter dropdown, search box, pagination controls
  - Empty state when no releases
  - Click handler to navigate to detail

- [ ] T013 [P] [US1] Create reusable Pagination component in src/frontend/src/components/Pagination.tsx
  - Props: currentPage, totalPages, onPageChange
  - Render: Bootstrap pagination with Previous/Next and page numbers

- [ ] T014 [US1] Enhance src/frontend/src/pages/releases/index.astro to use ReleaseList component
  - Replace existing content with <ReleaseList client:load />
  - Ensure layout and navigation work correctly

- [ ] T015 [US1] Add status badge styling to Bootstrap CSS or component
  - DRAFT: yellow badge
  - PUBLISHED: green badge
  - ARCHIVED: gray badge

- [ ] T016 [US1] Implement debounced search (300ms delay) in ReleaseList
  - Use useEffect with setTimeout for debouncing
  - Clear timeout on cleanup

**Validation**: Run E2E tests - all 6 tests should now PASS

**Checkpoint**: Release list is fully functional - users can browse, filter, search, paginate

---

## Phase 2: User Story 2 - Create New Release (Priority: P1) 🎯 MVP

**Goal**: ADMIN/RELEASE_MANAGER can create releases with validation

**Independent Test**: Click "Create Release", fill form, submit, see new release in list with DRAFT status

### Tests for User Story 2 (TDD - Write First) ⚠️

- [ ] T017 [P] [US2] E2E test: Create button visible for ADMIN/RELEASE_MANAGER only in src/frontend/tests/e2e/releases/release-create.spec.ts
- [ ] T018 [P] [US2] E2E test: Modal opens with form fields (version, name, description)
- [ ] T019 [P] [US2] E2E test: Semantic version validation rejects "abc" and "1.0"
- [ ] T020 [P] [US2] E2E test: Duplicate version rejected with error message
- [ ] T021 [P] [US2] E2E test: Success creates release with DRAFT status
- [ ] T022 [P] [US2] E2E test: Release appears in list after creation
- [ ] T023 [P] [US2] E2E test: Warning shown when no requirements exist

**Expected Result**: All 7 tests FAIL

### Implementation for User Story 2

- [ ] T024 [P] [US2] Create ReleaseCreateModal component in src/frontend/src/components/ReleaseCreateModal.tsx
  - Props: isOpen, onClose, onSuccess
  - State: formData {version, name, description}, loading, error
  - Validation: Semantic versioning regex /^\d+\.\d+\.\d+$/
  - Submit: Call releaseService.create(), handle success/error
  - Render: Bootstrap modal with form fields

- [ ] T025 [P] [US2] Add semantic version validation function in ReleaseCreateModal
  - Function: validateSemanticVersion(version: string): boolean
  - Inline validation with error message display

- [ ] T026 [US2] Add "Create Release" button to ReleaseList component
  - Show only if user has ADMIN or RELEASE_MANAGER role
  - Role check: hasRole(['ADMIN', 'RELEASE_MANAGER'])
  - Click opens ReleaseCreateModal

- [ ] T027 [US2] Add success callback to refresh release list after creation
  - Pass onSuccess prop to modal
  - Modal calls onSuccess() after successful creation
  - ReleaseList refetches data

- [ ] T028 [US2] Add toast notification for success/error states
  - Use Bootstrap Toast or simple alert component
  - Success: "Release v{version} created successfully"
  - Error: Display error message from API

**Validation**: Run E2E tests - all 7 tests should now PASS

**Checkpoint**: MVP COMPLETE - users can browse and create releases 🎉

---

## Phase 3: User Story 3 - View Release Details (Priority: P2)

**Goal**: Users can view release metadata and requirement snapshots

**Independent Test**: Click release from list, see detail page with metadata and paginated snapshots

### Tests for User Story 3 (TDD - Write First) ⚠️

- [ ] T029 [P] [US3] E2E test: Detail page shows all metadata in src/frontend/tests/e2e/releases/release-detail.spec.ts
- [ ] T030 [P] [US3] E2E test: Snapshots table displays correctly with key columns
- [ ] T031 [P] [US3] E2E test: Pagination works for many snapshots (50 per page)
- [ ] T032 [P] [US3] E2E test: Click snapshot shows complete details (modal/expanded)
- [ ] T033 [P] [US3] E2E test: Export button downloads file with release data

**Expected Result**: All 5 tests FAIL

### Implementation for User Story 3

- [ ] T034 [P] [US3] Create ReleaseDetail component in src/frontend/src/components/ReleaseDetail.tsx
  - Props: releaseId (from URL)
  - State: release, snapshots, loading, error, pagination (page, totalPages)
  - useEffect: Fetch release and snapshots on mount and pagination change
  - Render: Metadata section, snapshots table, pagination, export button

- [ ] T035 [P] [US3] Create SnapshotDetailModal component in src/frontend/src/components/SnapshotDetailModal.tsx
  - Props: snapshot, isOpen, onClose
  - Render: Bootstrap modal showing all snapshot fields

- [ ] T036 [US3] Create dynamic page src/frontend/src/pages/releases/[id].astro
  - Use Astro dynamic routing
  - Extract id from params
  - Pass id to ReleaseDetail component

- [ ] T037 [US3] Add export button to ReleaseDetail
  - Button triggers download: /api/requirements/export/xlsx?releaseId={id}
  - Handle both Excel and Word formats
  - Show loading state during download

- [ ] T038 [US3] Add snapshot table with key columns
  - Columns: shortreq, chapter, norm, details (preview), motivation (preview)
  - Click row opens SnapshotDetailModal

**Validation**: Run E2E tests - all 5 tests should now PASS

**Checkpoint**: Users can drill down into release details and view snapshots

---

## Phase 4: User Story 4 - Compare Two Releases (Priority: P2)

**Goal**: Users can compare releases with side-by-side diff and Excel export

**Independent Test**: Navigate to /releases/compare, select two releases, see diff

### Tests for User Story 4 (TDD - Write First) ⚠️

- [ ] T039 [P] [US4] E2E test: Dropdowns populated with all releases in src/frontend/tests/e2e/releases/release-compare.spec.ts
- [ ] T040 [P] [US4] E2E test: Comparison shows Added (green), Deleted (red), Modified (yellow)
- [ ] T041 [P] [US4] E2E test: Field-by-field diff for modified requirements
- [ ] T042 [P] [US4] E2E test: Empty state when no differences
- [ ] T043 [P] [US4] E2E test: Cannot compare release with itself (validation)
- [ ] T044 [P] [US4] E2E test: Export comparison button downloads Excel file
- [ ] T045 [P] [US4] E2E test: Excel file has Change Type column with color coding

**Expected Result**: All 8 tests FAIL (note: 8 scenarios, 7 initially listed + Excel format check)

### Implementation for User Story 4

- [ ] T046 [P] [US4] Enhance ReleaseComparison component in src/frontend/src/components/ReleaseComparison.tsx
  - Add state: fromReleaseId, toReleaseId, comparisonData, loading, error
  - Add validation: cannot compare release with itself
  - Fetch comparison data when both releases selected
  - Render: Added (green), Deleted (red), Modified (yellow) sections

- [ ] T047 [P] [US4] Add field-by-field diff display for modified requirements
  - Show old value vs new value for each changed field
  - Highlight changed fields within modified requirements

- [ ] T048 [P] [US4] Create comparison export utility in src/frontend/src/utils/comparisonExport.ts
  - Function: exportComparisonToExcel(comparison)
  - Use exceljs library to generate Excel file
  - Single sheet with Change Type column
  - Color coding: green rows (Added), red rows (Deleted), yellow rows (Modified)

- [ ] T049 [US4] Add "Export Comparison Report" button to ReleaseComparison
  - Button visible when comparison data exists
  - Click calls exportComparisonToExcel()
  - Downloads file: comparison_{fromVersion}_vs_{toVersion}.xlsx

- [ ] T050 [US4] Enhance src/frontend/src/pages/releases/compare.astro if needed
  - Ensure ReleaseComparison component has all required props
  - Add loading state and error handling

**Validation**: Run E2E tests - all 8 tests should now PASS

**Checkpoint**: Users can compare releases and export comparison results

---

## Phase 5: User Story 5 - Manage Release Status Lifecycle (Priority: P2)

**Goal**: ADMIN/RELEASE_MANAGER can transition status (DRAFT → PUBLISHED → ARCHIVED)

**Independent Test**: View DRAFT release, click "Publish", verify status changes to PUBLISHED

### Tests for User Story 5 (TDD - Write First) ⚠️

- [ ] T051 [P] [US5] E2E test: DRAFT shows "Publish" button for ADMIN/RELEASE_MANAGER in src/frontend/tests/e2e/releases/release-status.spec.ts
- [ ] T052 [P] [US5] E2E test: Publish confirmation modal appears
- [ ] T053 [P] [US5] E2E test: Confirm publish transitions DRAFT to PUBLISHED
- [ ] T054 [P] [US5] E2E test: PUBLISHED shows "Archive" button
- [ ] T055 [P] [US5] E2E test: Archive transitions PUBLISHED to ARCHIVED
- [ ] T056 [P] [US5] E2E test: Status badge updates immediately after transition
- [ ] T057 [P] [US5] E2E test: USER does not see status transition buttons

**Expected Result**: All 7 tests FAIL

### Implementation for User Story 5

- [ ] T058 [P] [US5] Create ReleaseStatusActions component in src/frontend/src/components/ReleaseStatusActions.tsx
  - Props: release, currentUserRoles, onStatusChange
  - Render: "Publish" button if status=DRAFT and user is ADMIN/RELEASE_MANAGER
  - Render: "Archive" button if status=PUBLISHED and user is ADMIN/RELEASE_MANAGER
  - Click opens confirmation modal

- [ ] T059 [P] [US5] Create StatusTransitionModal component in src/frontend/src/components/StatusTransitionModal.tsx
  - Props: release, newStatus, isOpen, onClose, onConfirm
  - Render: Confirmation message with release version and new status
  - Confirm button calls API to update status

- [ ] T060 [US5] Add status update method to releaseService.ts
  - Method: updateStatus(id: number, status: 'PUBLISHED' | 'ARCHIVED')
  - Endpoint: PUT /api/releases/{id}/status with body {status}
  - Return updated release object

- [ ] T061 [US5] Integrate ReleaseStatusActions into ReleaseList component
  - Add to each release card/row
  - Pass onStatusChange callback to refresh list

- [ ] T062 [US5] Integrate ReleaseStatusActions into ReleaseDetail component
  - Add to metadata section
  - Pass onStatusChange callback to refresh release data

- [ ] T063 [US5] Verify backend endpoint PUT /api/releases/{id}/status exists
  - Test manually with curl or Postman
  - If missing, document as blocker (requires backend addition)

**Validation**: Run E2E tests - all 7 tests should now PASS

**Checkpoint**: Users can manage release status lifecycle

---

## Phase 6: User Story 7 - Export Requirements with Release Selection (Priority: P2)

**Goal**: Users can export requirements from specific releases

**Independent Test**: Navigate to export page, select release, download file with that release's data

### Tests for User Story 7 (TDD - Write First) ⚠️

- [ ] T064 [P] [US7] E2E test: Export page has release selector dropdown in src/frontend/tests/e2e/releases/release-export.spec.ts
- [ ] T065 [P] [US7] E2E test: Release selector defaults to "Current (latest)"
- [ ] T066 [P] [US7] E2E test: Selecting release passes releaseId to Excel export
- [ ] T067 [P] [US7] E2E test: Selecting release passes releaseId to Word export
- [ ] T068 [P] [US7] E2E test: Translated exports work with release selection

**Expected Result**: All 5 tests FAIL

### Implementation for User Story 7

- [ ] T069 [P] [US7] Verify ReleaseSelector component exists and works in src/frontend/src/components/ReleaseSelector.tsx
  - Props: selectedReleaseId, onReleaseChange
  - Fetches all releases on mount
  - Dropdown with options: "Current (latest)" and all releases
  - Calls onReleaseChange when selection changes

- [ ] T070 [US7] Integrate ReleaseSelector into src/frontend/src/pages/export.astro
  - Add state: selectedReleaseId (default: null for current)
  - Add ReleaseSelector component
  - Pass selectedReleaseId to export API calls (if not null)

- [ ] T071 [US7] Integrate ReleaseSelector into src/frontend/src/pages/import-export.astro
  - Same integration as export.astro
  - Maintain selection when switching between export formats

- [ ] T072 [US7] Update export button handlers to include releaseId parameter
  - Excel: /api/requirements/export/xlsx?releaseId={id}
  - Word: /api/requirements/export/docx?releaseId={id}
  - Translated: /api/requirements/export/{format}/translated/{lang}?releaseId={id}

- [ ] T073 [US7] Add visual indicator showing which release is being exported
  - Display: "Exporting from: v1.0.0 - Q4 2024 Audit" or "Exporting: Current (latest)"

**Validation**: Run E2E tests - all 5 tests should now PASS

**Checkpoint**: Export functionality works with release selection

---

## Phase 7: User Story 6 - Delete Release (Priority: P3)

**Goal**: ADMIN/RELEASE_MANAGER can delete releases with granular permissions

**Independent Test**: ADMIN deletes any release, RELEASE_MANAGER deletes only own release

### Tests for User Story 6 (TDD - Write First) ⚠️

- [ ] T074 [P] [US6] E2E test: ADMIN sees delete button on all releases in src/frontend/tests/e2e/releases/release-delete.spec.ts
- [ ] T075 [P] [US6] E2E test: RELEASE_MANAGER sees delete only on releases they created
- [ ] T076 [P] [US6] E2E test: Delete confirmation modal appears with warning
- [ ] T077 [P] [US6] E2E test: Confirm delete removes release from list
- [ ] T078 [P] [US6] E2E test: USER does not see delete buttons
- [ ] T079 [P] [US6] E2E test: RELEASE_MANAGER cannot delete others' releases (403 error)
- [ ] T080 [P] [US6] E2E test: Network error displays error message

**Expected Result**: All 7 tests FAIL

### Implementation for User Story 6

- [ ] T081 [P] [US6] Create ReleaseDeleteConfirm component in src/frontend/src/components/ReleaseDeleteConfirm.tsx
  - Props: release, isOpen, onClose, onConfirm
  - Render: Bootstrap modal with warning message
  - Warning: "Are you sure you want to delete release v{version}? This will remove all requirement snapshots and cannot be undone."
  - Confirm button calls delete API

- [ ] T082 [P] [US6] Add delete permission check utility in src/frontend/src/utils/permissions.ts
  - Function: canDeleteRelease(release, currentUser, currentUserRoles)
  - Logic: return isAdmin || (isReleaseManager && release.createdBy === currentUser.username)

- [ ] T083 [US6] Add delete button to ReleaseList component
  - Show button based on canDeleteRelease() check
  - Click opens ReleaseDeleteConfirm modal
  - onConfirm calls releaseService.delete() and refreshes list

- [ ] T084 [US6] Add delete button to ReleaseDetail component
  - Same permission logic as ReleaseList
  - After successful delete, navigate back to list

- [ ] T085 [US6] Add error handling for 403 Forbidden responses
  - Display: "You do not have permission to delete this release."
  - Keep release in list on error

- [ ] T086 [US6] Add success notification for deletion
  - Toast: "Release v{version} deleted successfully"

**Validation**: Run E2E tests - all 7 tests should now PASS

**Checkpoint**: Delete functionality works with correct RBAC enforcement

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Final polish, accessibility, performance, error handling

- [ ] T087 [P] Add keyboard navigation support (Tab, Enter, Escape) to all modals and forms
- [ ] T088 [P] Add ARIA labels for screen readers to all interactive elements
- [ ] T089 [P] Verify color contrast meets WCAG AA standards (use contrast checker)
- [ ] T090 [P] Add text labels to status badges (not just color-coded)
- [ ] T091 [P] Optimize ReleaseList re-renders with React.memo for child components
- [ ] T092 [P] Add skeleton loaders for initial page loads (list, detail, comparison)
- [ ] T093 [P] Add disabled states to buttons during loading (prevent double-clicks)
- [ ] T094 [P] Implement consistent error handling pattern across all components
  - Network errors: "Failed to load releases. Please try again."
  - 403 errors: "You do not have permission to perform this action."
  - 404 errors: "Release not found."
- [ ] T095 [P] Update CLAUDE.md with new components and pages documentation
- [ ] T096 [P] Add JSDoc comments to releaseService.ts methods
- [ ] T097 [P] Remove all console.log statements (replace with proper error logging if needed)
- [ ] T098 [P] Run ESLint and fix all warnings in src/frontend/src/components/ and src/frontend/src/pages/
- [ ] T099 [P] Format all files with Prettier (if configured)
- [ ] T100 [P] Verify all toast notifications are consistent (success=green, error=red, warning=yellow)

**Validation**:
- All E2E tests still pass (18 scenarios across 7 files)
- No console errors in browser
- Lighthouse score >90 (Performance, Accessibility, Best Practices)
- Manual keyboard navigation test passes
- Manual screen reader spot-check (test with VoiceOver/NVDA)

**Checkpoint**: Feature is production-ready with high quality and accessibility

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundation (Phase 0)**: No dependencies - must complete before all user stories
- **User Story 1 (Phase 1 - P1)**: Depends on Foundation - BLOCKS MVP
- **User Story 2 (Phase 2 - P1)**: Depends on Foundation - BLOCKS MVP
  - **After Phase 2: MVP COMPLETE** 🎉
- **User Story 3 (Phase 3 - P2)**: Depends on Foundation - can start after Phase 0
- **User Story 4 (Phase 4 - P2)**: Depends on Foundation - can start after Phase 0
- **User Story 5 (Phase 5 - P2)**: Depends on Foundation + User Story 1 (needs ReleaseList) - can work in parallel with US3/US4
- **User Story 7 (Phase 6 - P2)**: Depends on Foundation - can start after Phase 0
- **User Story 6 (Phase 7 - P3)**: Depends on Foundation + User Story 1 (needs ReleaseList) - can work in parallel with others
- **Polish (Phase 8)**: Depends on all user stories complete

### User Story Independence

All user stories are independently testable after Foundation is complete:
- **US1 (Browse)**: Independent - just needs service layer
- **US2 (Create)**: Independent - just needs service layer
- **US3 (Detail)**: Independent - just needs service layer + routing
- **US4 (Compare)**: Independent - just needs service layer
- **US5 (Status)**: Requires US1 (ReleaseList component exists)
- **US6 (Delete)**: Requires US1 (ReleaseList component exists)
- **US7 (Export)**: Independent - just needs ReleaseSelector

### Parallel Opportunities

**Foundation (Phase 0)**: All 5 tasks can run in parallel (T001-T005)

**Within Each User Story**:
- All E2E tests can be written in parallel (marked [P])
- Component creation can happen in parallel if different files (marked [P])

**Across User Stories** (after Foundation):
- US1, US2, US3, US4, US7 can all be developed in parallel by different developers
- US5 and US6 can start once US1 (ReleaseList) is created

### Example Parallel Execution

**Day 1 (Foundation)**:
```
Parallel: T001, T002, T003, T004, T005
```

**Days 2-4 (MVP - if team of 2)**:
```
Developer A: US1 (T006-T016)
Developer B: US2 (T017-T028)
```

**Days 5-8 (P2 features - if team of 3)**:
```
Developer A: US3 (T029-T038)
Developer B: US4 (T039-T050)
Developer C: US7 (T064-T073)
Then US5 (T051-T063) and US6 (T074-T086)
```

---

## Task Summary

### By Phase
- **Phase 0 (Foundation)**: 5 tasks (T001-T005)
- **Phase 1 (US1 - List)**: 11 tasks (6 tests + 5 implementation) (T006-T016)
- **Phase 2 (US2 - Create)**: 12 tasks (7 tests + 5 implementation) (T017-T028)
- **Phase 3 (US3 - Detail)**: 10 tasks (5 tests + 5 implementation) (T029-T038)
- **Phase 4 (US4 - Compare)**: 12 tasks (8 tests + 4 implementation) (T039-T050)
- **Phase 5 (US5 - Status)**: 13 tasks (7 tests + 6 implementation) (T051-T063)
- **Phase 6 (US7 - Export)**: 10 tasks (5 tests + 5 implementation) (T064-T073)
- **Phase 7 (US6 - Delete)**: 13 tasks (7 tests + 6 implementation) (T074-T086)
- **Phase 8 (Polish)**: 14 tasks (T087-T100)

**Total**: 100 tasks

### By Type
- **E2E Tests**: 45 tests (across 7 test files)
- **Components**: 8 new + 2 enhanced = 10 components
- **Pages**: 1 new + 3 enhanced = 4 pages
- **Services**: 1 service layer (releaseService.ts)
- **Utilities**: 2 utilities (comparisonExport, permissions)
- **Polish**: 14 quality/accessibility tasks

### By Priority
- **P1 (MVP)**: Phase 0 + Phase 1 + Phase 2 = 28 tasks
- **P2**: Phase 3 + Phase 4 + Phase 5 + Phase 6 = 45 tasks
- **P3**: Phase 7 = 13 tasks
- **Polish**: Phase 8 = 14 tasks

---

## Critical Path

The critical path to MVP (Phase 2 complete):

```
Phase 0 (Foundation) → Phase 1 (US1 - List) → Phase 2 (US2 - Create)
    5 tasks         →      11 tasks         →      12 tasks
    (Day 1)         →    (Days 2-3)         →    (Days 3-4)

= MVP: 28 tasks over 4 days
```

After MVP, all P2 features can be developed in parallel if team capacity allows.

---

## Testing Checklist

Before marking feature complete, verify:

- [ ] All 45 E2E tests pass (7 test files)
- [ ] Manual testing with 3 user roles (USER, ADMIN, RELEASE_MANAGER)
- [ ] Manual testing with different release counts (0, 1, 50, 100+)
- [ ] Manual testing with large snapshots (500, 1000+)
- [ ] Performance targets met:
  - [ ] List loads <2s (100 releases)
  - [ ] Detail loads <3s (1000 snapshots)
  - [ ] Comparison completes <3s (1000 vs 1000)
  - [ ] Status transitions <1s
- [ ] Accessibility:
  - [ ] Keyboard navigation works for all interactions
  - [ ] Screen reader announces important actions
  - [ ] Color contrast WCAG AA compliant
- [ ] Error scenarios tested:
  - [ ] Network failure (disconnect during action)
  - [ ] 403 Forbidden (wrong permissions)
  - [ ] 404 Not Found (invalid release ID)
  - [ ] Validation errors (invalid version format, duplicate)
- [ ] Cross-browser testing:
  - [ ] Chrome (latest)
  - [ ] Firefox (latest)
  - [ ] Safari (latest)
  - [ ] Edge (latest)
- [ ] Documentation updated (CLAUDE.md)
- [ ] No console errors or warnings
- [ ] ESLint passes with no errors
- [ ] Lighthouse score >90

---

## Notes

- **TDD Approach**: All E2E tests are written FIRST before implementation (marked with ⚠️ NOTE)
- **[P] Marker**: Tasks marked [P] can run in parallel with others in the same phase
- **[Story] Marker**: Tasks marked [US#] belong to specific user story for traceability
- **Exact File Paths**: All file paths are explicit for clarity
- **Checkpoints**: Each user story phase has a checkpoint to validate independent functionality
- **MVP Milestone**: After Phase 2 (T028), feature can be demoed with browse + create functionality

**Ready to start**: Begin with T001 (create releaseService.ts) and proceed sequentially through Foundation, then move to MVP user stories.
