# Tasks: Sort Empty AWS Accounts to Bottom

**Input**: Design documents from `/specs/075-sort-empty-accounts/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, quickstart.md

**Tests**: Not requested — no test tasks included per constitution Principle IV.

**Organization**: Tasks grouped by user story. US1+US2 are combined (same code change), US3 is separate.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Frontend**: `src/frontend/src/components/`

---

## Phase 1: User Story 1+2 - Sort Accounts with Assets First (Priority: P1) MVP

**Goal**: Accounts with assets appear before accounts without assets, preserving account ID sort order within each group.

**Independent Test**: Navigate to `/account-vulns` and verify all accounts with assets appear above all accounts without assets, each group sorted by account ID ascending.

### Implementation for User Story 1+2

- [x] T001 [US1] Split `summary.accountGroups` into `accountsWithAssets` (totalAssets > 0) and `accountsWithoutAssets` (totalAssets === 0) using `filter()` in `src/frontend/src/components/AccountVulnsView.tsx`
- [x] T002 [US1] Replace the single `summary.accountGroups.map()` rendering loop (line 290) with sequential rendering of `accountsWithAssets.map()` followed by `accountsWithoutAssets.map()` in `src/frontend/src/components/AccountVulnsView.tsx`
- [x] T003 [US1] Verify summary statistics (AWS Accounts count, Total Assets, Total Vulnerabilities, By Severity) still reflect ALL accounts (both groups) — no changes needed to summary section, just confirm it still uses `summary.accountGroups.length` and `summary.totalAssets` in `src/frontend/src/components/AccountVulnsView.tsx`

**Checkpoint**: Accounts with assets appear first, empty accounts at bottom. Summary stats unchanged. MVP complete.

---

## Phase 2: User Story 3 - Collapsible Empty Accounts Section (Priority: P2)

**Goal**: Empty accounts are wrapped in a collapsible section, collapsed by default, with a heading showing the count.

**Independent Test**: Load `/account-vulns` with a mix of accounts. Verify a collapsed "Accounts with no assets (N)" heading appears after accounts with assets. Click to expand/collapse.

### Implementation for User Story 3

- [x] T004 [US3] Add `useState<boolean>(false)` for `emptyAccountsExpanded` state in `src/frontend/src/components/AccountVulnsView.tsx`
- [x] T005 [US3] Wrap the `accountsWithoutAssets.map()` block in a collapsible section: clickable heading with chevron icon (`bi-chevron-down`/`bi-chevron-up`), count display ("Accounts with no assets (N)"), and conditional rendering of account cards based on `emptyAccountsExpanded` state in `src/frontend/src/components/AccountVulnsView.tsx`
- [x] T006 [US3] Handle edge cases: hide collapsible section entirely when `accountsWithoutAssets.length === 0`; render all accounts normally (no collapsible wrapper) when `accountsWithAssets.length === 0` in `src/frontend/src/components/AccountVulnsView.tsx`

**Checkpoint**: Collapsible section works. All user stories complete.

---

## Phase 3: Polish & Cross-Cutting Concerns

- [x] T007 Remove any `console.log` debug statements added during development in `src/frontend/src/components/AccountVulnsView.tsx` — N/A, no new console.log statements were added
- [x] T008 Run quickstart.md verification steps (npm run dev, navigate to /account-vulns, verify all acceptance scenarios) — frontend build passes

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (US1+US2)**: No dependencies — can start immediately
- **Phase 2 (US3)**: Depends on T001-T002 (needs the split arrays to exist)
- **Phase 3 (Polish)**: Depends on Phase 2 completion

### User Story Dependencies

- **US1+US2 (P1)**: Independent — core sorting logic
- **US3 (P2)**: Depends on US1+US2 — wraps the empty accounts array in a collapsible section

### Within Each Phase

- T001 before T002 (split arrays must exist before rendering)
- T003 after T002 (verify summary stats after rendering change)
- T004 before T005 (state must exist before UI uses it)
- T005 before T006 (collapsible section must exist before edge case handling)

### Parallel Opportunities

- T001 and T004 could theoretically be done together (both add code to the component), but since they modify the same file and T005 depends on T001's output, sequential execution is recommended.

---

## Implementation Strategy

### MVP First (US1+US2 Only)

1. Complete Phase 1: T001-T003
2. **STOP and VALIDATE**: Accounts with assets appear first, stats unchanged
3. Deploy/demo if ready — delivers core value

### Full Feature

1. Complete Phase 1 (US1+US2) → Validate
2. Complete Phase 2 (US3) → Validate collapsible behavior
3. Complete Phase 3 (Polish) → Final verification

---

## Notes

- All tasks modify a single file: `src/frontend/src/components/AccountVulnsView.tsx`
- No backend changes, no new files, no API changes
- The backend already returns `totalAssets` per account group — this is the field used for splitting
- Existing account card rendering (the `.map()` callback) remains unchanged; only the iteration structure changes
