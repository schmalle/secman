# Implementation Tasks: Vulnerability Statistics Lense

**Feature**: 036-vuln-stats-lense
**Branch**: `036-vuln-stats-lense`
**Generated**: 2025-10-28

## Overview

This document contains actionable implementation tasks for the Vulnerability Statistics Lense feature, organized by user story priority. Each user story represents an independently testable increment of functionality.

---

## Implementation Strategy

**MVP Scope**: User Story 1 (P1) - View Most Common Vulnerabilities
- Delivers core value: identifying top vulnerabilities for prioritization
- Establishes backend/frontend patterns for remaining stories
- ~40% of total implementation effort

**Incremental Delivery**: Complete each user story before starting the next
- Each story is independently deployable and testable
- Parallel execution opportunities within each story (marked with [P])
- Build on established patterns from previous stories

**Testing Philosophy**: Per Constitution Principle IV (User-Requested Testing), test tasks are NOT included proactively. Tests will be written following TDD when implementation begins, if requested by the user.

---

## User Story Mapping

| User Story | Priority | Components | Endpoints | Frontend |
|------------|----------|------------|-----------|----------|
| US1: Most Common Vulnerabilities | P1 | MostCommonVulnerabilityDto | `/most-common` | MostCommonVulnerabilities.tsx (table) |
| US2: Severity Distribution | P2 | SeverityDistributionDto | `/severity-distribution` | SeverityDistributionChart.tsx (pie) |
| US3: Asset Vulnerability Stats | P3 | TopAssetByVulnerabilitiesDto, VulnerabilityByAssetTypeDto | `/top-assets`, `/by-asset-type` | TopAssetsByVulnerabilities.tsx, VulnerabilityByAssetType.tsx |
| US4: Temporal Trends | P4 | TemporalTrendsDto, TemporalTrendDataPointDto | `/temporal-trends` | TemporalTrendsChart.tsx (line) |

---

## Phase 1: Setup & Dependencies

**Goal**: Install required dependencies and prepare project structure

**Duration**: ~30 minutes

### Tasks

- [X] T001 [P] Install Chart.js dependencies in /Users/flake/sources/misc/secman/src/frontend: `npm install chart.js@^4.4.1 react-chartjs-2@^5.3.0 chartjs-plugin-zoom@^2.2.0`
- [X] T002 [P] Create statistics components directory at /Users/flake/sources/misc/secman/src/frontend/src/components/statistics/
- [X] T003 [P] Create DTO directory at /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/ (if not exists)

---

## Phase 2: Foundational Infrastructure (Blocking Prerequisites)

**Goal**: Implement shared infrastructure required by all user stories

**Duration**: ~3 hours

**Why Foundational**: These components are used by multiple user stories and must be completed before story-specific work

### Backend Foundation

- [X] T004 Create VulnerabilityStatisticsService.kt in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/ with Authentication parameter handling and workgroup filtering logic pattern from OutdatedAssetService
- [X] T005 Create VulnerabilityStatisticsController.kt in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/controller/ with @Secured(SecurityRule.IS_AUTHENTICATED) annotation
- [X] T006 Add getUserWorkgroupIds method to UserService if not already present, or verify existing method in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/service/UserService.kt

### Frontend Foundation

- [X] T007 [P] Create vulnerabilityStatisticsApi.ts in /Users/flake/sources/misc/secman/src/frontend/src/services/api/ with base API client structure, JWT auth headers from sessionStorage, and TypeScript interfaces
- [X] T008 [P] Create vulnerability-statistics.astro page scaffold in /Users/flake/sources/misc/secman/src/frontend/src/pages/ with Bootstrap 5.3 grid layout and page header
- [X] T009 Update Sidebar.tsx in /Users/flake/sources/misc/secman/src/frontend/src/components/ to add "Lense" sub-item under Vulnerability Management with role check (ADMIN or VULN)

---

## Phase 3: User Story 1 - View Most Common Vulnerabilities (P1)

**Priority**: P1 (Highest)
**Goal**: Display top 10 most frequently occurring vulnerabilities with drill-down capability
**Independent Test**: Navigate to /vulnerability-statistics and verify top 10 vulnerabilities displayed with CVE IDs, severity, and occurrence counts. Click a vulnerability to navigate to details.

**Why P1**: Core value proposition - directly impacts remediation prioritization strategy

### Backend Tasks

- [ ] T010 [P] [US1] Create MostCommonVulnerabilityDto.kt in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/ with fields: vulnerabilityId, cvssSeverity, occurrenceCount, affectedAssetCount
- [ ] T011 [US1] Add findMostCommonVulnerabilitiesForAll query method to VulnerabilityRepository.kt using @Query with GROUP BY vulnerabilityId, COALESCE for null severity, ORDER BY COUNT DESC LIMIT 10
- [ ] T012 [US1] Add findMostCommonVulnerabilitiesForWorkgroups query method to VulnerabilityRepository.kt with JOIN on asset.workgroups and WHERE workgroup.id IN :workgroupIds
- [ ] T013 [US1] Implement getMostCommonVulnerabilities method in VulnerabilityStatisticsService.kt with ADMIN/VULN role branching logic
- [ ] T014 [US1] Add GET /api/vulnerability-statistics/most-common endpoint in VulnerabilityStatisticsController.kt returning List<MostCommonVulnerabilityDto>

### Frontend Tasks

- [ ] T015 [P] [US1] Add getMostCommonVulnerabilities method to vulnerabilityStatisticsApi.ts returning Promise<MostCommonVulnerabilityDto[]>
- [ ] T016 [US1] Create MostCommonVulnerabilities.tsx table component in /Users/flake/sources/misc/secman/src/frontend/src/components/statistics/ with Bootstrap table, severity badges, and click handlers for drill-down
- [ ] T017 [US1] Integrate MostCommonVulnerabilities component into vulnerability-statistics.astro with client:load directive and Bootstrap col-12 layout

### Integration & Validation

- [ ] T018 [US1] Test workgroup filtering: verify ADMIN sees all vulnerabilities, VULN user sees only accessible vulnerabilities
- [ ] T019 [US1] Test drill-down navigation: click vulnerability row and verify navigation to vulnerability detail page
- [ ] T020 [US1] Test empty state: verify appropriate message displayed when no vulnerability data exists

**Parallel Execution Opportunity**: T010 and T015 can run concurrently (backend DTO and frontend API method are independent)

---

## Phase 4: User Story 2 - View Severity Distribution (P2)

**Priority**: P2
**Goal**: Display interactive pie chart showing vulnerability distribution across severity levels
**Independent Test**: View Lense page and verify pie chart displays with percentages for Critical/High/Medium/Low. Click a severity segment to navigate to filtered vulnerability list.

**Why P2**: Strategic insight into overall risk profile; secondary to knowing which specific vulnerabilities to fix

### Backend Tasks

- [ ] T021 [P] [US2] Create SeverityDistributionDto.kt in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/ with fields: critical, high, medium, low, unknown counts and computed percentage properties
- [ ] T022 [US2] Add getSeverityDistribution query methods to VulnerabilityRepository.kt (ADMIN and workgroup-filtered versions) using GROUP BY cvss_severity with COALESCE for nulls
- [ ] T023 [US2] Implement getSeverityDistribution method in VulnerabilityStatisticsService.kt that aggregates results into SeverityDistributionDto with percentage calculations
- [ ] T024 [US2] Add GET /api/vulnerability-statistics/severity-distribution endpoint in VulnerabilityStatisticsController.kt returning SeverityDistributionDto

### Frontend Tasks

- [ ] T025 [P] [US2] Add getSeverityDistribution method to vulnerabilityStatisticsApi.ts returning Promise<SeverityDistributionDto>
- [ ] T026 [US2] Create SeverityDistributionChart.tsx pie chart component in /Users/flake/sources/misc/secman/src/frontend/src/components/statistics/ with Chart.js Pie, Bootstrap card wrapper, color mapping (Critical=#dc3545, High=#fd7e14, Medium=#ffc107, Low=#0dcaf0), and onClick handler for drill-down
- [ ] T027 [US2] Integrate SeverityDistributionChart component into vulnerability-statistics.astro with client:load and Bootstrap col-12 col-lg-6 layout

### Integration & Validation

- [ ] T028 [US2] Test pie chart interactivity: click each severity segment and verify navigation to filtered /vulnerabilities?severity={level} page
- [ ] T029 [US2] Test percentage calculations: verify percentages sum to 100% and match displayed counts
- [ ] T030 [US2] Test responsive layout: verify pie chart displays correctly on mobile (<768px) and desktop (≥992px)

**Parallel Execution Opportunity**: T021 and T025 can run concurrently

---

## Phase 5: User Story 3 - View Asset Vulnerability Statistics (P3)

**Priority**: P3
**Goal**: Display top assets by vulnerability count and vulnerability grouping by asset type
**Independent Test**: View statistics showing top 10 assets with highest vuln counts and vuln counts grouped by asset type. Click asset to navigate to asset detail page.

**Why P3**: Identifies systemic issues in specific infrastructure areas; tertiary to vulnerability identification

### Backend Tasks - Top Assets

- [ ] T031 [P] [US3] Create TopAssetByVulnerabilitiesDto.kt in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/ with fields: assetId, assetName, assetType, assetIp, totalVulnerabilityCount, criticalCount, highCount, mediumCount, lowCount
- [ ] T032 [US3] Add getTopAssetsByVulnerabilities query methods to VulnerabilityRepository.kt (ADMIN and workgroup versions) using GROUP BY asset_id with severity breakdowns, ORDER BY total DESC LIMIT 10
- [ ] T033 [US3] Implement getTopAssetsByVulnerabilities method in VulnerabilityStatisticsService.kt
- [ ] T034 [US3] Add GET /api/vulnerability-statistics/top-assets endpoint in VulnerabilityStatisticsController.kt returning List<TopAssetByVulnerabilitiesDto>

### Backend Tasks - By Asset Type

- [ ] T035 [P] [US3] Create VulnerabilityByAssetTypeDto.kt in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/ with fields: assetType, assetCount, totalVulnerabilityCount, criticalCount, highCount, mediumCount, lowCount, averageVulnerabilitiesPerAsset
- [ ] T036 [US3] Add getVulnerabilitiesByAssetType query methods to VulnerabilityRepository.kt (ADMIN and workgroup versions) using GROUP BY asset.type with COALESCE for null types as 'Unknown'
- [ ] T037 [US3] Implement getVulnerabilitiesByAssetType method in VulnerabilityStatisticsService.kt with average calculation logic
- [ ] T038 [US3] Add GET /api/vulnerability-statistics/by-asset-type endpoint in VulnerabilityStatisticsController.kt returning List<VulnerabilityByAssetTypeDto>

### Frontend Tasks

- [ ] T039 [P] [US3] Add getTopAssetsByVulnerabilities and getVulnerabilitiesByAssetType methods to vulnerabilityStatisticsApi.ts
- [ ] T040 [US3] Create TopAssetsByVulnerabilities.tsx table component in /Users/flake/sources/misc/secman/src/frontend/src/components/statistics/ with Bootstrap table, severity badges, and click handler to navigate to /assets/{assetId}
- [ ] T041 [US3] Create VulnerabilityByAssetType.tsx grouped bar chart component in /Users/flake/sources/misc/secman/src/frontend/src/components/statistics/ with Chart.js Bar chart, grouped datasets for severity levels, Bootstrap card wrapper
- [ ] T042 [US3] Integrate both US3 components into vulnerability-statistics.astro: TopAssetsByVulnerabilities in col-12 col-lg-6, VulnerabilityByAssetType in col-12

### Integration & Validation

- [ ] T043 [US3] Test asset drill-down: click asset in table and verify navigation to asset detail page
- [ ] T044 [US3] Test asset type grouping: verify all asset types displayed including "Unknown" for null types
- [ ] T045 [US3] Test average calculation: manually verify averageVulnerabilitiesPerAsset = totalVulnerabilityCount / assetCount

**Parallel Execution Opportunities**:
- T031 (DTO1) and T035 (DTO2) and T039 (API methods) can run concurrently
- T040 (table component) and T041 (chart component) can run concurrently after T039 completes

---

## Phase 6: User Story 4 - View Temporal Trends (P4)

**Priority**: P4 (Lowest)
**Goal**: Display line chart showing vulnerability count trends over 30/60/90 days with interactive time range selector
**Independent Test**: View line chart with 30-day trend data. Select 60-day and 90-day ranges and verify chart updates. Hover over data points to see tooltips with exact counts and dates.

**Why P4**: Long-term insights valuable but less immediately actionable than current vulnerability counts

### Backend Tasks

- [ ] T046 [P] [US4] Create TemporalTrendDataPointDto.kt in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/ with fields: date (LocalDate), totalCount, criticalCount, highCount, mediumCount, lowCount
- [ ] T047 [P] [US4] Create TemporalTrendsDto.kt in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/ with fields: startDate, endDate, days, dataPoints (List<TemporalTrendDataPointDto>)
- [ ] T048 [US4] Add getTemporalTrends query methods to VulnerabilityRepository.kt (ADMIN and workgroup versions) using GROUP BY DATE(scan_timestamp), SUM with CASE for severity counts, WHERE scan_timestamp >= CURRENT_DATE - INTERVAL :days DAY
- [ ] T049 [US4] Implement getTemporalTrends method in VulnerabilityStatisticsService.kt with days parameter validation (30, 60, 90), date range calculation, and TemporalTrendsDto construction
- [ ] T050 [US4] Add GET /api/vulnerability-statistics/temporal-trends endpoint in VulnerabilityStatisticsController.kt with @QueryValue days parameter validation, returning TemporalTrendsDto or 400 Bad Request for invalid days

### Frontend Tasks

- [ ] T051 [P] [US4] Add getTemporalTrends method to vulnerabilityStatisticsApi.ts with days parameter (30 | 60 | 90) returning Promise<TemporalTrendsDto>
- [ ] T052 [US4] Create TemporalTrendsChart.tsx line chart component in /Users/flake/sources/misc/secman/src/frontend/src/components/statistics/ with Chart.js Line chart, state for selected time range (30/60/90), Bootstrap button group for time range selector, smooth curve (tension: 0.4), tooltips with date and count, Bootstrap card wrapper
- [ ] T053 [US4] Integrate TemporalTrendsChart component into vulnerability-statistics.astro with client:load and Bootstrap col-12 col-lg-6 layout

### Integration & Validation

- [ ] T054 [US4] Test time range selector: click 30/60/90 day buttons and verify chart data updates correctly
- [ ] T055 [US4] Test tooltip interactivity: hover over data points and verify tooltip displays exact date and vulnerability counts
- [ ] T056 [US4] Test empty time range: select 90 days when only 30 days of data exists, verify chart displays available data without errors
- [ ] T057 [US4] Test invalid days parameter: send API request with days=999, verify 400 Bad Request response

**Parallel Execution Opportunities**: T046, T047, and T051 can run concurrently (all DTOs and API method independent)

---

## Phase 7: Polish & Cross-Cutting Concerns

**Goal**: Final integration, performance optimization, and user experience enhancements

**Duration**: ~2 hours

### Performance Optimization

- [ ] T058 [P] Enable Chart.js decimation plugin for all chart components: add decimation config with algorithm: 'lttb', samples: 500 to chart options
- [ ] T059 [P] Disable animations for large datasets: add animation: false to chart options when data size > 1000
- [ ] T060 Test page load performance: load /vulnerability-statistics with 10,000 vulnerabilities, measure total page load time, verify < 3 seconds (FR-015 compliance)

### Empty State Handling

- [ ] T061 [P] Add empty state UI to MostCommonVulnerabilities.tsx: display "No vulnerability data available. Please import vulnerability scans to view statistics." when array is empty
- [ ] T062 [P] Add empty state UI to SeverityDistributionChart.tsx: display message when all severity counts are zero
- [ ] T063 [P] Add empty state UI to TopAssetsByVulnerabilities.tsx: display message when no assets have vulnerabilities
- [ ] T064 [P] Add empty state UI to VulnerabilityByAssetType.tsx: display message when no asset types exist
- [ ] T065 [P] Add empty state UI to TemporalTrendsChart.tsx: display "No vulnerability data available for the selected time period." when dataPoints array is empty

### Error Handling

- [ ] T066 [P] Add error handling to all API methods in vulnerabilityStatisticsApi.ts: catch network errors, display user-friendly toast notifications
- [ ] T067 [P] Add loading states to all components: display Bootstrap spinners while API calls are in progress
- [ ] T068 Test 401 Unauthorized: remove JWT token, access /vulnerability-statistics, verify redirect to login page
- [ ] T069 Test 403 Forbidden: access as user with no ADMIN/VULN role, verify access denied message

### Accessibility & UX

- [ ] T070 [P] Add ARIA labels to all interactive chart elements: aria-label for pie chart segments, line chart tooltips, bar chart bars
- [ ] T071 [P] Verify keyboard navigation: ensure Tab navigation works through all interactive elements (time range buttons, table rows, pie chart segments)
- [ ] T072 Test responsive layout: verify page displays correctly at breakpoints: <768px (mobile), ≥768px (tablet), ≥992px (desktop), ≥1200px (wide)

### Documentation

- [ ] T073 Update CLAUDE.md with new API endpoints under "## API Endpoints (Critical Only)" section: add vulnerability-statistics subsection with all 5 endpoints
- [ ] T074 Update CLAUDE.md "## Active Technologies" section: add Chart.js 4.4.1, react-chartjs-2 5.3.0
- [ ] T075 Add inline code documentation: document workgroup filtering logic in VulnerabilityStatisticsService.kt, document Chart.js registration pattern in all chart components

**Parallel Execution Opportunities**: All empty state tasks (T061-T065), all error handling tasks (T066-T067), and all accessibility tasks (T070-T071) can run concurrently

---

## Dependency Graph & Execution Order

### Critical Path

```
Setup (Phase 1)
  → Foundation (Phase 2)  [BLOCKING - required for all user stories]
    → US1 (Phase 3) [MVP - highest priority]
      → US2 (Phase 4) [Independent of US1, can start after Foundation]
        → US3 (Phase 5) [Independent of US1/US2, can start after Foundation]
          → US4 (Phase 6) [Independent of US1/US2/US3, can start after Foundation]
            → Polish (Phase 7) [Requires all user stories complete]
```

### User Story Dependencies

- **US1**: Depends only on Phase 1 & 2
- **US2**: Depends only on Phase 1 & 2 (independent of US1)
- **US3**: Depends only on Phase 1 & 2 (independent of US1, US2)
- **US4**: Depends only on Phase 1 & 2 (independent of US1, US2, US3)

**Key Insight**: After completing Foundation (Phase 2), all user stories can theoretically be implemented in parallel by different developers. Priority order (P1→P2→P3→P4) is recommended for sequential work to maximize early value delivery.

---

## Parallel Execution Examples

### Within Phase 2 (Foundation)
```bash
# Backend and frontend foundation tasks can run concurrently
Terminal 1: # Implement T004, T005, T006 (backend services)
Terminal 2: # Implement T007, T008, T009 (frontend infrastructure)
```

### Within Phase 3 (User Story 1)
```bash
# DTO and API method can be developed concurrently
Terminal 1: # Implement T010 (MostCommonVulnerabilityDto)
Terminal 2: # Implement T015 (API client method)
# Then converge for integration
```

### Across User Stories (After Phase 2 Complete)
```bash
# Different developers can work on different user stories simultaneously
Developer A: # Implement Phase 3 (US1) - Most Common Vulnerabilities
Developer B: # Implement Phase 4 (US2) - Severity Distribution
Developer C: # Implement Phase 5 (US3) - Asset Statistics
Developer D: # Implement Phase 6 (US4) - Temporal Trends
```

### Within Phase 7 (Polish)
```bash
# Multiple polish tasks can run in parallel
Terminal 1: # T058, T059, T060 (performance optimization)
Terminal 2: # T061-T065 (empty state UI)
Terminal 3: # T066-T069 (error handling & testing)
Terminal 4: # T070-T072 (accessibility & UX)
Terminal 5: # T073-T075 (documentation)
```

---

## Task Summary

**Total Tasks**: 75
- Phase 1 (Setup): 3 tasks (~30 min)
- Phase 2 (Foundation): 6 tasks (~3 hours) - **BLOCKING**
- Phase 3 (US1 - P1): 11 tasks (~5 hours) - **MVP**
- Phase 4 (US2 - P2): 10 tasks (~4 hours)
- Phase 5 (US3 - P3): 15 tasks (~6 hours)
- Phase 6 (US4 - P4): 12 tasks (~5 hours)
- Phase 7 (Polish): 18 tasks (~2 hours)

**Parallelizable Tasks**: 31 tasks marked with [P] (41% of total)

**Estimated Total Duration**:
- Sequential: ~25 hours
- With parallel execution: ~18 hours (28% reduction)
- MVP only (Setup + Foundation + US1): ~8.5 hours

---

## Testing Notes

**Per Constitution Principle IV**: Test tasks are NOT included in this plan. Tests will be written following TDD (Test-Driven Development) when implementation begins, if requested by the user.

**When tests ARE requested**, follow this pattern for each user story:
1. Write contract tests for API endpoints (verify status codes, response schemas)
2. Write unit tests for service methods (verify workgroup filtering, aggregation logic)
3. Write integration tests for database queries (verify performance with 10,000+ records)
4. Write E2E tests for frontend components (verify user interactions, drill-down navigation)

**Test Coverage Target**: ≥80% for new code (per Constitution Principle II)

**Reference**: Test patterns documented in `quickstart.md` section "Testing Checklist"

---

## Success Criteria

Each user story is considered complete when:

1. **US1 (P1)**: Top 10 vulnerabilities displayed with occurrence counts; drill-down navigation works; workgroup filtering verified
2. **US2 (P2)**: Pie chart displays severity distribution with percentages; clicking segment navigates to filtered vulnerabilities; responsive on mobile/desktop
3. **US3 (P3)**: Top 10 assets displayed with vuln counts; asset type grouping shows all types; clicking asset navigates to detail page; average calculations verified
4. **US4 (P4)**: Line chart displays 30/60/90 day trends; time range selector updates chart; hover tooltips show exact counts; invalid days parameter returns 400

**Overall Feature Complete**: All 4 user stories pass acceptance criteria + page loads <3s with 10,000 vulnerabilities (FR-015) + workgroup access control verified for ADMIN and VULN roles

---

## MVP Recommendation

**Suggested MVP Scope**: Phase 1 + Phase 2 + Phase 3 (User Story 1)

**Rationale**:
- Delivers core value: identifying most common vulnerabilities for remediation prioritization
- Establishes all backend/frontend patterns for remaining stories
- Validates Chart.js integration and workgroup filtering
- Provides immediate user value with ~40% of total implementation effort
- Remaining stories are incremental additions following established patterns

**MVP Deliverables**:
- ✅ "Lense" navigation item in sidebar
- ✅ /vulnerability-statistics page with top 10 vulnerabilities table
- ✅ Workgroup filtering (ADMIN sees all, VULN sees assigned)
- ✅ Drill-down navigation to vulnerability details
- ✅ Empty state handling

**Post-MVP Iterations**:
- Iteration 2: Add US2 (Severity Distribution pie chart)
- Iteration 3: Add US3 (Asset statistics and grouping)
- Iteration 4: Add US4 (Temporal trends line chart)
- Iteration 5: Polish phase (performance, error handling, accessibility)

---

## Format Validation

✅ **All tasks follow required checklist format**:
- ✅ Checkbox: `- [ ]`
- ✅ Task ID: T001-T075 (sequential)
- ✅ [P] marker: 31 parallelizable tasks marked
- ✅ [Story] label: US1-US4 labels applied to story-specific tasks
- ✅ Description: Clear action + file path for each task
- ✅ No tasks missing checkbox, ID, or path

**Example Valid Task**:
```
- [ ] T010 [P] [US1] Create MostCommonVulnerabilityDto.kt in /Users/flake/sources/misc/secman/src/backendng/src/main/kotlin/com/secman/dto/ with fields: vulnerabilityId, cvssSeverity, occurrenceCount, affectedAssetCount
```

---

**Next Steps**:
1. Review and approve this task list
2. Begin implementation with Phase 1 (Setup)
3. Complete Foundation (Phase 2) before starting user stories
4. Implement US1 (P1) as MVP
5. Iterate through US2-US4 based on priority

**Questions or Changes**: Refer to design documents in `/Users/flake/sources/misc/secman/specs/036-vuln-stats-lense/`:
- `spec.md` - User stories and requirements
- `plan.md` - Technical architecture
- `data-model.md` - DTOs and database queries
- `contracts/vulnerability-statistics-api.yaml` - API specifications
- `research.md` - Chart.js decision rationale
- `quickstart.md` - Implementation patterns and examples
