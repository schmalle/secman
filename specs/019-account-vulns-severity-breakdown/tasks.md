# Tasks: Account Vulns Severity Breakdown

**Input**: Design documents from `/specs/019-account-vulns-severity-breakdown/`
**Prerequisites**: spec.md, plan.md, research.md, data-model.md, quickstart.md, contracts/account-vulns-api-severity.yaml
**Branch**: `019-account-vulns-severity-breakdown`
**Parent Feature**: 018-under-vuln-management (MUST be deployed first)

**Tests**: TDD is NON-NEGOTIABLE per constitution. All tests MUST be written FIRST and FAIL before implementation.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`, `src/backendng/src/test/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`, `tests/e2e/`

---

## Phase 1: Setup & Verification

**Purpose**: Verify parent feature (018) exists and is functional before extending it

- [ ] T001 Verify parent feature 018 is deployed: Check `src/backendng/src/main/kotlin/com/secman/service/AccountVulnsService.kt` exists
- [ ] T002 Verify existing DTOs exist: `AccountVulnsSummaryDto.kt`, `AccountGroupDto.kt`, `AssetVulnCountDto.kt` in `src/backendng/src/main/kotlin/com/secman/dto/`
- [ ] T003 Verify `/api/account-vulns` endpoint is functional: Run existing contract tests in `src/backendng/src/test/kotlin/com/secman/contract/AccountVulnsContractTest.kt`
- [ ] T004 Verify vulnerabilities table has severity field: Query `SELECT DISTINCT severity FROM vulnerabilities LIMIT 10` to check data distribution
- [ ] T005 [P] Check frontend components exist: `AccountVulnsView.tsx`, `AssetVulnTable.tsx` in `src/frontend/src/components/`

**Checkpoint**: Parent feature verified, database schema confirmed, ready to extend

---

## Phase 2: Foundational (DTO Extensions)

**Purpose**: Extend existing DTOs with optional severity fields for backward compatibility

**⚠️ CRITICAL**: These changes MUST maintain backward compatibility. All severity fields are optional/nullable.

### Backend DTO Extensions

- [ ] T006 [P] [Foundation] Extend `AssetVulnCountDto.kt` in `src/backendng/src/main/kotlin/com/secman/dto/`: Add optional fields `criticalCount: Int? = null`, `highCount: Int? = null`, `mediumCount: Int? = null`
- [ ] T007 [P] [Foundation] Extend `AccountGroupDto.kt` in `src/backendng/src/main/kotlin/com/secman/dto/`: Add optional fields `totalCritical: Int? = null`, `totalHigh: Int? = null`, `totalMedium: Int? = null`
- [ ] T008 [Foundation] Extend `AccountVulnsSummaryDto.kt` in `src/backendng/src/main/kotlin/com/secman/dto/`: Add optional fields `globalCritical: Int? = null`, `globalHigh: Int? = null`, `globalMedium: Int? = null` (depends on T006, T007)

### Frontend Interface Extensions

- [ ] T009 [P] [Foundation] Update TypeScript interfaces in `src/frontend/src/services/accountVulnsService.ts`: Add optional severity fields to `AssetVulnCount`, `AccountGroup`, `AccountVulnsSummary` interfaces

### Helper Data Structure

- [ ] T010 [Foundation] Create private data class `SeverityCounts` in `AccountVulnsService.kt` with fields: total, critical, high, medium, low, unknown (Int), plus validate() method that logs error if sum != total

**Checkpoint**: DTOs extended, compile-time checked, no runtime behavior changed yet

---

## Phase 3: User Story 1 - View Severity Breakdown Per Asset (Priority: P1) 🎯 MVP

**Goal**: Display critical, high, medium vulnerability counts for each asset in addition to total count

**Independent Test**: Create asset with 5 CRITICAL, 12 HIGH, 8 MEDIUM vulnerabilities, load Account Vulns page, verify asset row displays all three severity badges with correct counts, including "Critical: 5", "High: 12", "Medium: 8"

### Tests for User Story 1 (Write FIRST, ensure FAIL)

- [ ] T011 [P] [US1] Add test to `AccountVulnsServiceTest.kt` in `src/backendng/src/test/kotlin/com/secman/service/`: Test `countVulnerabilitiesBySeverity()` returns correct SeverityCounts for asset with mixed severities
- [ ] T012 [P] [US1] Add test to `AccountVulnsServiceTest.kt`: Test vulnerability counting handles NULL severity (counts as UNKNOWN)
- [ ] T013 [P] [US1] Add test to `AccountVulnsServiceTest.kt`: Test severity normalization (lowercase "critical" → CRITICAL)
- [ ] T014 [P] [US1] Add test to `AccountVulnsServiceTest.kt`: Test validation logs error when counts don't sum to total
- [ ] T015 [P] [US1] Update contract test in `AccountVulnsContractTest.kt` in `src/backendng/src/test/kotlin/com/secman/contract/`: Assert response includes non-null severity fields for all assets
- [ ] T016 [P] [US1] Add contract test in `AccountVulnsContractTest.kt`: Verify severity counts are non-negative integers
- [ ] T017 [P] [US1] Create test fixtures in `AccountVulnsTestFixtures.kt` in `src/backendng/src/test/kotlin/com/secman/fixtures/`: Method `createVulnerabilitiesWithSeverity()` returns 8 vulns (2 critical, 3 high, 1 medium, 1 low, 1 null)
- [ ] T018 [P] [US1] Add E2E test in `account-vulns.spec.ts` in `tests/e2e/`: Test `should display severity badges for each asset` - verify Critical/High/Medium badges visible

**Run all User Story 1 tests** - Expected: ALL FAIL (severity counting not implemented yet)

### Backend Implementation for User Story 1

- [ ] T019 [US1] Implement `countVulnerabilitiesBySeverity()` method in `AccountVulnsService.kt` in `src/backendng/src/main/kotlin/com/secman/service/`:
  - Accept `assetIds: List<Long>` parameter
  - Build SQL query with CASE-based conditional aggregation: `SUM(CASE WHEN UPPER(severity) = 'CRITICAL' THEN 1 ELSE 0 END)`
  - Execute query for all severity levels: CRITICAL, HIGH, MEDIUM, LOW, NULL/UNKNOWN
  - Return `Map<Long, SeverityCounts>` mapping asset ID to counts
  - Call `validate()` on each SeverityCounts instance
  
- [ ] T020 [US1] Modify `getAccountVulnsSummary()` method in `AccountVulnsService.kt`:
  - After getting asset IDs, call `countVulnerabilitiesBySeverity(assetIds)`
  - When building AssetVulnCountDto objects, populate criticalCount, highCount, mediumCount from SeverityCounts map
  - Keep existing total count logic for backward compatibility

### Frontend Implementation for User Story 1

- [ ] T021 [P] [US1] Create `SeverityBadge.tsx` component in `src/frontend/src/components/`:
  - Props: `severity: 'CRITICAL' | 'HIGH' | 'MEDIUM'`, `count: number`, `className?: string`
  - Config object with bgClass, icon (bi-exclamation-triangle-fill, bi-arrow-up-circle-fill, bi-dash-circle-fill), label, ariaLabel
  - Render Bootstrap badge with icon, label, and count (e.g., "Critical: 5")
  - Include aria-hidden on icon, visually-hidden span for screen readers
  
- [ ] T022 [US1] Update `AssetVulnTable.tsx` in `src/frontend/src/components/`:
  - Import SeverityBadge component
  - Add new table column header "Severity Breakdown"
  - For each asset row, add cell with 3 SeverityBadge components (CRITICAL, HIGH, MEDIUM)
  - Use optional chaining: `asset.criticalCount ?? 0` for fallback
  - Ensure responsive layout with flexbox gap-1

**Run all User Story 1 tests** - Expected: ALL PASS ✅

**Checkpoint**: Per-asset severity badges display correctly, backend counts accurately, User Story 1 COMPLETE

---

## Phase 4: User Story 2 - View Account-Level Severity Aggregation (Priority: P1)

**Goal**: Display aggregated severity counts in each AWS account group header to help users prioritize accounts

**Independent Test**: Create 2 AWS accounts with 3 assets each having different vulnerabilities, verify each account header shows correct totals (e.g., Account1: "3 critical, 10 high, 8 medium", Account2: "1 critical, 5 high, 12 medium")

### Tests for User Story 2 (Write FIRST, ensure FAIL)

- [ ] T023 [P] [US2] Add test to `AccountVulnsServiceTest.kt`: Test account-level severity aggregation sums correctly across multiple assets
- [ ] T024 [P] [US2] Add test to `AccountVulnsServiceTest.kt`: Test account with no vulnerabilities shows 0 for all severities
- [ ] T025 [P] [US2] Add contract test in `AccountVulnsContractTest.kt`: Verify AccountGroupDto has non-null totalCritical, totalHigh, totalMedium
- [ ] T026 [P] [US2] Add E2E test in `account-vulns.spec.ts`: Test `should display account-level severity totals in header` - verify account header shows all three severity badges

**Run all User Story 2 tests** - Expected: ALL FAIL (account aggregation not implemented)

### Backend Implementation for User Story 2

- [ ] T027 [US2] Modify `getAccountVulnsSummary()` in `AccountVulnsService.kt` to aggregate severity at account level:
  - After building assetDtos list within account group, compute: `val totalCritical = assetDtos.sumOf { it.criticalCount ?: 0 }`
  - Similarly for totalHigh, totalMedium
  - Populate AccountGroupDto with these aggregated values

### Frontend Implementation for User Story 2

- [ ] T028 [US2] Update `AccountVulnsView.tsx` in `src/frontend/src/components/`:
  - Import SeverityBadge component
  - Locate account group header (`.card-header.bg-primary`)
  - Add flex container with gap-2 for badges
  - Render 3 SeverityBadge components using group.totalCritical, group.totalHigh, group.totalMedium
  - Add responsive design for mobile (stack vertically if needed)

**Run all User Story 2 tests** - Expected: ALL PASS ✅

**Checkpoint**: Account-level severity aggregation working, headers show totals, User Story 2 COMPLETE

---

## Phase 5: User Story 3 - Global Severity Summary (Priority: P1)

**Goal**: Display overall severity breakdown across all AWS accounts in top summary cards for at-a-glance security posture

**Independent Test**: Create 3 AWS accounts with varying vulnerabilities, verify top summary section shows correct global totals (e.g., "Total: 18 critical, 67 high, 89 medium" summed across all accounts)

### Tests for User Story 3 (Write FIRST, ensure FAIL)

- [ ] T029 [P] [US3] Add test to `AccountVulnsServiceTest.kt`: Test global severity aggregation sums correctly across multiple accounts
- [ ] T030 [P] [US3] Add test to `AccountVulnsServiceTest.kt`: Test user with no vulnerabilities shows 0 for all global severities
- [ ] T031 [P] [US3] Add contract test in `AccountVulnsContractTest.kt`: Verify AccountVulnsSummaryDto has non-null globalCritical, globalHigh, globalMedium
- [ ] T032 [P] [US3] Add E2E test in `account-vulns.spec.ts`: Test `should display global severity summary in top cards` - verify summary section shows severity breakdown

**Run all User Story 3 tests** - Expected: ALL FAIL (global aggregation not implemented)

### Backend Implementation for User Story 3

- [ ] T033 [US3] Modify `getAccountVulnsSummary()` in `AccountVulnsService.kt` to aggregate severity globally:
  - After building all account groups, compute: `val globalCritical = accountGroups.sumOf { it.totalCritical ?: 0 }`
  - Similarly for globalHigh, globalMedium
  - Populate AccountVulnsSummaryDto with these global values

### Frontend Implementation for User Story 3

- [ ] T034 [US3] Update `AccountVulnsView.tsx` in `src/frontend/src/components/`:
  - Locate summary stats section (`.row.mb-4`)
  - Modify existing 3-column layout to 4-column or convert one column to severity display
  - Add new card titled "By Severity" or update existing "Total Vulnerabilities" card
  - Render 3 SeverityBadge components in vertical stack (d-flex flex-column gap-1)
  - Use summary.globalCritical, summary.globalHigh, summary.globalMedium with ?? 0 fallback

**Run all User Story 3 tests** - Expected: ALL PASS ✅

**Checkpoint**: Global severity summary displaying, complete security posture visible, User Story 3 COMPLETE

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Refinement, documentation, performance validation, accessibility

### Performance & Optimization

- [ ] T035 [P] [Polish] Measure baseline performance: Run `curl -w "@curl-format.txt"` against `/api/account-vulns` before severity changes, record time and response size
- [ ] T036 [Polish] Measure with severity: Same curl command after implementation, verify time increase ≤10%, size increase ≤30%
- [ ] T037 [Polish] If performance regression >10%: Add database index on `vulnerabilities.severity` field using migration script in `scripts/`, re-measure

### Accessibility Validation

- [ ] T038 [P] [Polish] Test color-blind accessibility: Use Chrome DevTools to emulate protanopia/deuteranopia, verify severity badges distinguishable by icons not just colors
- [ ] T039 [P] [Polish] Test screen reader: Use VoiceOver/NVDA to verify SeverityBadge aria-labels are announced correctly
- [ ] T040 [P] [Polish] Test keyboard navigation: Tab through severity badges, verify focus indicators visible and logical order

### Mobile Responsiveness

- [ ] T041 [P] [Polish] Test mobile view: Resize browser to 320px width, verify severity badges stack properly and remain readable
- [ ] T042 [P] [Polish] Test tablet view: Resize to 768px width, verify account header badges wrap gracefully

### Documentation

- [ ] T043 [P] [Polish] Update API documentation: Add severity fields to OpenAPI spec at `specs/019-account-vulns-severity-breakdown/contracts/account-vulns-api-severity.yaml` (already created, verify completeness)
- [ ] T044 [P] [Polish] Add inline code comments: Document SeverityCounts validation logic and SQL query strategy in `AccountVulnsService.kt`
- [ ] T045 [P] [Polish] Update component documentation: Add JSDoc comments to `SeverityBadge.tsx` explaining accessibility features

### Backward Compatibility Verification

- [ ] T046 [Polish] Test with old API consumers: Create test that deserializes response using old DTO (without severity fields), verify no errors
- [ ] T047 [Polish] Verify null field handling: Check Micronaut Serde serialization includes or omits null fields as expected

### Data Quality Check

- [ ] T048 [Polish] Run data quality query: `SELECT COUNT(*) FROM vulnerabilities WHERE severity IS NULL OR severity NOT IN ('CRITICAL','HIGH','MEDIUM','LOW')` - document count of UNKNOWN vulnerabilities
- [ ] T049 [Polish] Log review: Check application logs for severity validation errors (mismatched sums), document any findings

**Checkpoint**: All polish tasks complete, feature production-ready

---

## Dependency Graph

**User Story Dependencies**: All three user stories are INDEPENDENT and can be implemented in any order once Foundation phase is complete.

```
Phase 1 (Setup) → Phase 2 (Foundation) → Phase 3 (US1) → Phase 6 (Polish)
                                       ↘ Phase 4 (US2) ↗
                                       ↘ Phase 5 (US3) ↗
```

**Optimal Sequence**: 
1. Phase 1, Phase 2 (MUST complete first)
2. Phase 3 (US1 - MVP, ship this first)
3. Phase 4 (US2) + Phase 5 (US3) in parallel if desired
4. Phase 6 (Polish)

**MVP Scope**: Phase 1 + Phase 2 + Phase 3 (US1 only) = Per-asset severity badges
**Full Scope**: All phases = Complete severity breakdown at all levels

---

## Parallel Execution Examples

### Within Phase 2 (Foundation)
```bash
# All DTO extensions can run in parallel (different files)
T006, T007, T008, T009, T010 → 5 parallel tasks
```

### Within Phase 3 (User Story 1)
```bash
# Tests can all run in parallel (different test files)
T011, T012, T013, T014, T015, T016, T017, T018 → 8 parallel tasks

# Frontend components while backend being implemented
T021 (SeverityBadge) parallel with T019 (backend countVulnerabilitiesBySeverity)
```

### Across User Stories (after Foundation)
```bash
# If multiple developers available, implement stories in parallel
Developer A: Phase 3 (US1 - per-asset)
Developer B: Phase 4 (US2 - account-level)
Developer C: Phase 5 (US3 - global)
```

### Within Phase 6 (Polish)
```bash
# Most polish tasks are independent
T035, T038, T039, T040, T041, T042, T043, T044, T045, T048 → 10 parallel tasks
```

---

## Task Statistics

**Total Tasks**: 49
- Phase 1 (Setup): 5 tasks
- Phase 2 (Foundation): 5 tasks
- Phase 3 (US1 - Per-Asset): 14 tasks (8 tests + 6 implementation)
- Phase 4 (US2 - Account-Level): 6 tasks (4 tests + 2 implementation)
- Phase 5 (US3 - Global): 6 tasks (4 tests + 2 implementation)
- Phase 6 (Polish): 13 tasks

**Parallel Opportunities**: 28 tasks marked [P] (57%)

**Test Coverage**: 22 test tasks (45% of total)
- Unit tests: 11 tasks
- Contract tests: 6 tasks
- E2E tests: 5 tasks

**By User Story**:
- Foundation: 5 tasks
- US1 (MVP): 14 tasks → ~4-6 hours
- US2: 6 tasks → ~2 hours
- US3: 6 tasks → ~2 hours
- Polish: 13 tasks → ~3-4 hours

**MVP Estimate**: Setup + Foundation + US1 = 24 tasks = ~6-8 hours
**Full Feature Estimate**: All tasks = 49 tasks = ~12-16 hours

---

## Implementation Strategy

### Recommended Approach: Incremental Delivery

1. **Week 1**: MVP (Phases 1-3)
   - Ship per-asset severity badges
   - Get user feedback on badge design/placement
   - Validate performance with real data

2. **Week 2**: Full Feature (Phases 4-5)
   - Add account-level aggregation
   - Add global summary
   - Users get complete severity visibility

3. **Week 2-3**: Polish (Phase 6)
   - Address feedback from week 1
   - Optimize performance if needed
   - Accessibility audit

### Testing Strategy

**TDD Workflow** (per phase):
1. Write all tests for phase (marked [P] can run parallel)
2. Verify ALL tests FAIL
3. Implement minimal code to make tests PASS
4. Refactor while keeping tests GREEN
5. Move to next phase

**Integration Points**:
- After Phase 3: Full E2E test of per-asset display
- After Phase 4: Test multi-account scenario
- After Phase 5: Test full user journey with all severity levels
- After Phase 6: Performance benchmark + accessibility audit

---

## Success Criteria Validation

Map tasks to success criteria from spec.md:

| Success Criteria | Validated By Tasks |
|------------------|-------------------|
| SC-001: Users identify critical vulns in 5s | T018, T026, T032 (E2E tests verify display) |
| SC-002: 90% interpret correctly | T038, T039 (accessibility validation) |
| SC-003: 100% accuracy in counts | T011-T014, T023-T024, T029-T030 (unit tests) |
| SC-004: ≤10% load time increase | T035-T037 (performance measurement) |
| SC-005: ≤30% response size increase | T035-T036 (response size measurement) |
| SC-006: Zero miscategorization | T013, T014 (normalization tests) |
| SC-007: Mobile responsive 320px | T041-T042 (mobile testing) |
| SC-008: Color-blind accessible | T038 (color-blind emulation) |

---

**Generated**: 2025-10-14
**Ready for Implementation**: ✅ All tasks defined with clear acceptance criteria
**Next Command**: Begin with Phase 1 Setup tasks
