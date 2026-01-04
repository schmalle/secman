# Tasks: Vulnerability Statistics Domain Filter

**Input**: Design documents from `/specs/059-vuln-stats-domain-filter/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Not included (tests only when explicitly requested per Constitution Principle IV)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Frontend**: `src/frontend/src/`

---

## Phase 1: Setup

**Purpose**: No setup tasks needed - extending existing web application structure

**Checkpoint**: Existing codebase ready for extension

---

## Phase 2: Foundational (Backend API Infrastructure)

**Purpose**: Backend API changes that MUST be complete before frontend work begins

**⚠️ CRITICAL**: Frontend user stories depend on these backend endpoints being available

- [X] T001 [P] Create AvailableDomainsDto in `src/backendng/src/main/kotlin/com/secman/dto/AvailableDomainsDto.kt`
- [X] T002 Add getAvailableDomains() method to VulnerabilityStatisticsService in `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityStatisticsService.kt`
- [X] T003 Add GET /available-domains endpoint to VulnerabilityStatisticsController in `src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityStatisticsController.kt`
- [X] T004 [P] Add optional domain parameter to getMostCommonVulnerabilities() in `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityStatisticsService.kt`
- [X] T005 [P] Add optional domain parameter to getMostVulnerableProducts() in `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityStatisticsService.kt`
- [X] T006 [P] Add optional domain parameter to getSeverityDistribution() in `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityStatisticsService.kt`
- [X] T007 Add @QueryValue domain parameter to existing controller endpoints in `src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityStatisticsController.kt`
- [X] T008 Run ./gradlew build to verify backend compiles and tests pass

**Checkpoint**: Backend API ready - all endpoints accept optional domain parameter

---

## Phase 3: User Story 1 - Filter Statistics by Domain (Priority: P1) 🎯 MVP

**Goal**: Users can filter all vulnerability statistics by selecting a domain from a dropdown

**Independent Test**: Select a domain from dropdown, verify all three statistics components (Top 10 Vulnerabilities, Top 10 Products, Severity Distribution) update to show only data from that domain

### Implementation for User Story 1

- [X] T009 [P] [US1] Add getAvailableDomains() method to vulnerabilityStatisticsApi.ts in `src/frontend/src/services/api/vulnerabilityStatisticsApi.ts`
- [X] T010 [P] [US1] Add optional domain parameter to existing API methods (getMostCommon, getMostVulnerableProducts, getSeverityDistribution) in `src/frontend/src/services/api/vulnerabilityStatisticsApi.ts`
- [X] T011 [US1] Create DomainSelector.tsx component with loading state in `src/frontend/src/components/statistics/DomainSelector.tsx`
- [X] T012 [US1] Add domain prop to MostCommonVulnerabilities.tsx in `src/frontend/src/components/statistics/MostCommonVulnerabilities.tsx`
- [X] T013 [P] [US1] Add domain prop to MostVulnerableProducts.tsx in `src/frontend/src/components/statistics/MostVulnerableProducts.tsx`
- [X] T014 [P] [US1] Add domain prop to SeverityDistributionChart.tsx in `src/frontend/src/components/statistics/SeverityDistributionChart.tsx`
- [X] T015 [US1] Integrate DomainSelector into vulnerability-statistics.astro page in `src/frontend/src/pages/vulnerability-statistics.astro`
- [X] T016 [US1] Wire domain state from page to all statistics components in `src/frontend/src/pages/vulnerability-statistics.astro`
- [X] T017 [US1] Run npm run build to verify frontend compiles

**Checkpoint**: User Story 1 complete - domain filtering works end-to-end

---

## Phase 4: User Story 2 - Persist Domain Selection (Priority: P2)

**Goal**: Domain selection persists in sessionStorage so users don't lose their selection when navigating

**Independent Test**: Select a domain, navigate away, return to page, verify the domain is still selected

### Implementation for User Story 2

- [X] T018 [US2] Add sessionStorage read on DomainSelector mount in `src/frontend/src/components/statistics/DomainSelector.tsx`
- [X] T019 [US2] Add sessionStorage write on domain selection change in `src/frontend/src/components/statistics/DomainSelector.tsx`
- [X] T020 [US2] Clear sessionStorage key when "All Domains" selected in `src/frontend/src/components/statistics/DomainSelector.tsx`

**Checkpoint**: User Story 2 complete - selection persists across navigation

---

## Phase 5: User Story 3 - Display Domain Context (Priority: P3)

**Goal**: Visual indicator shows which domain is currently selected for context

**Independent Test**: Select a domain, verify a badge or indicator appears showing the active filter

### Implementation for User Story 3

- [X] T021 [US3] Add total asset count display to DomainSelector showing "(X assets)" in `src/frontend/src/components/statistics/DomainSelector.tsx`
- [X] T022 [US3] Add active filter badge when domain selected (not "All Domains") in `src/frontend/src/components/statistics/DomainSelector.tsx`

**Checkpoint**: User Story 3 complete - visual context indicator shown

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation and integration verification

- [X] T023 Run ./gradlew build to verify full backend build passes
- [X] T024 Run npm run build to verify full frontend build passes
- [ ] T025 Manual integration test: verify domain filter works with existing access control (RBAC)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No setup needed
- **Foundational (Phase 2)**: Backend API - BLOCKS all frontend work
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - US1 must complete before US2 (persistence needs selector component)
  - US1 must complete before US3 (visual context needs selector component)
  - US2 and US3 can proceed in parallel after US1
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Depends on User Story 1 (DomainSelector component must exist)
- **User Story 3 (P3)**: Depends on User Story 1 (DomainSelector component must exist)

### Within Each User Story

- Backend changes before frontend changes
- API methods before components
- Components before page integration
- Core implementation before refinements

### Parallel Opportunities

**Foundational Phase**:
```
Parallel Group A: T001 (DTO)
Parallel Group B: T004, T005, T006 (service methods - after T002)
```

**User Story 1**:
```
Parallel Group A: T009, T010 (API methods)
Parallel Group B: T012, T013, T014 (component props - after T011)
```

**User Story 2 & 3**: Can run in parallel after US1 completes

---

## Parallel Example: Foundational Phase

```bash
# Launch DTO creation:
Task: "Create AvailableDomainsDto in src/backendng/.../dto/AvailableDomainsDto.kt"

# After T002 completes, launch domain parameter additions in parallel:
Task: "Add optional domain parameter to getMostCommonVulnerabilities()"
Task: "Add optional domain parameter to getMostVulnerableProducts()"
Task: "Add optional domain parameter to getSeverityDistribution()"
```

---

## Parallel Example: User Story 1

```bash
# Launch API method updates in parallel:
Task: "Add getAvailableDomains() method to vulnerabilityStatisticsApi.ts"
Task: "Add optional domain parameter to existing API methods"

# After T011 completes, launch component prop additions in parallel:
Task: "Add domain prop to MostCommonVulnerabilities.tsx"
Task: "Add domain prop to MostVulnerableProducts.tsx"
Task: "Add domain prop to SeverityDistributionChart.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 2: Foundational (Backend API)
2. Complete Phase 3: User Story 1 (Domain filtering)
3. **STOP and VALIDATE**: Test domain filter works end-to-end
4. Deploy/demo if ready

### Incremental Delivery

1. Complete Foundational → Backend API ready
2. Add User Story 1 → Core filtering works → Deploy/Demo (MVP!)
3. Add User Story 2 → Selection persists → Deploy/Demo
4. Add User Story 3 → Visual context → Deploy/Demo
5. Each story adds value without breaking previous stories

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Domain filtering is additive to existing RBAC - never bypasses access control
- Session storage key: `secman.vuln-stats.selectedDomain`
- Domain values normalized to lowercase on backend
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
