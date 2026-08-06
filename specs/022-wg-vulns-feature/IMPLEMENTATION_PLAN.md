# WG Vulns Feature - Implementation Plan

**Feature ID**: 022  
**Feature Name**: WG Vulns - Workgroup-Based Vulnerability View  
**Status**: 📋 Ready for Implementation  
**Created**: 2025-10-16  
**Estimated Duration**: 5-6 days  

## 📊 Executive Summary

This plan provides a detailed breakdown of tasks, dependencies, and timeline for implementing the WG Vulns feature. The feature adds a new vulnerability view grouped by workgroups, mirroring the Account Vulns pattern.

## 🎯 Project Goals

1. ✅ Enable non-admin users to view vulnerabilities grouped by their workgroups
2. ✅ Maintain UI/UX consistency with Account Vulns feature
3. ✅ Ensure robust access control and security
4. ✅ Achieve <2 second page load time
5. ✅ Maintain >80% code coverage

## 👥 Team Structure

### Required Roles
- **Backend Developer** (1 person, 2-3 days)
- **Frontend Developer** (1 person, 2-3 days)
- **QA Engineer** (1 person, 1-2 days, parallel)
- **Tech Lead/Reviewer** (0.5 days for reviews)

### Optional Roles
- **UX Designer** (0.5 days for UI review)
- **DevOps Engineer** (0.5 days for deployment support)

## 📅 Timeline Overview

```
Day 1:  Backend DTOs + Repositories + Service start
Day 2:  Backend Service + Controller + Tests
Day 3:  Frontend Service + Page + Component start
Day 4:  Frontend Component + Sidebar + Tests
Day 5:  Integration testing + Bug fixes
Day 6:  Final review + Documentation + Deployment
```

## 🏗️ Phase Breakdown

---

## Phase 1: Backend Foundation (Days 1-2)

### 1.1 Create DTOs
**Duration**: 2 hours  
**Assignee**: Backend Developer  
**Priority**: P0 (Critical)

**Tasks**:
- [ ] Create `WorkgroupVulnsSummaryDto.kt`
  - Copy structure from `AccountVulnsSummaryDto.kt`
  - Fields: workgroupGroups, totalAssets, totalVulnerabilities, global severity counts
  - Add `@Serdeable` annotation
  - Add KDoc comments

- [ ] Create `WorkgroupGroupDto.kt`
  - Copy structure from `AccountGroupDto.kt`
  - Fields: workgroupId, workgroupName, workgroupDescription, assets, totals, severity counts
  - Reuse `AssetVulnCountDto` for assets list
  - Add `@Serdeable` annotation
  - Add KDoc comments

**Files to Create**:
```
src/backendng/src/main/kotlin/com/secman/dto/
├── WorkgroupVulnsSummaryDto.kt (NEW)
└── WorkgroupGroupDto.kt (NEW)
```

**Acceptance Criteria**:
- DTOs compile without errors
- All fields properly typed and documented
- Serialization/deserialization works correctly

**Reference**:
- `src/backendng/src/main/kotlin/com/secman/dto/AccountVulnsSummaryDto.kt`
- `src/backendng/src/main/kotlin/com/secman/dto/AccountGroupDto.kt`

---

### 1.2 Update Repositories
**Duration**: 2 hours  
**Assignee**: Backend Developer  
**Priority**: P0 (Critical)  
**Dependencies**: None

**Tasks**:
- [ ] Update `WorkgroupRepository.kt`
  - Add method: `findWorkgroupsByUserEmail(email: String): List<Workgroup>`
  - Use `@Query` with JOIN to user_workgroups table
  - Add KDoc documentation
  - Consider using JOIN FETCH to avoid lazy loading issues

- [ ] Update `AssetRepository.kt`
  - Add method: `findByWorkgroupIdIn(workgroupIds: List<Long>): List<Asset>`
  - Use `@Query` with JOIN to asset_workgroups table
  - Consider JOIN FETCH for workgroups relationship
  - Add KDoc documentation

**Files to Modify**:
```
src/backendng/src/main/kotlin/com/secman/repository/
├── WorkgroupRepository.kt (UPDATE)
└── AssetRepository.kt (UPDATE)
```

**Implementation Notes**:
```kotlin
// WorkgroupRepository.kt
@Query("SELECT DISTINCT w FROM Workgroup w JOIN w.users u WHERE u.email = :email")
fun findWorkgroupsByUserEmail(email: String): List<Workgroup>

// AssetRepository.kt
@Query("SELECT DISTINCT a FROM Asset a JOIN a.workgroups w WHERE w.id IN :workgroupIds")
fun findByWorkgroupIdIn(workgroupIds: List<Long>): List<Asset>
```

**Acceptance Criteria**:
- Repository methods compile and work with database
- Queries return correct results
- JOIN FETCH prevents N+1 query issues

**Reference**:
- `src/backendng/src/main/kotlin/com/secman/repository/UserMappingRepository.kt`

---

### 1.3 Create Service
**Duration**: 4-6 hours  
**Assignee**: Backend Developer  
**Priority**: P0 (Critical)  
**Dependencies**: Tasks 1.1, 1.2

**Tasks**:
- [ ] Create `WorkgroupVulnsService.kt`
  - Inject repositories: WorkgroupRepository, AssetRepository, VulnerabilityRepository, EntityManager
  - Implement `getWorkgroupVulnsSummary(authentication: Authentication)` method
  - Implement `countVulnerabilitiesBySeverity(assetIds: List<Long>)` helper method
  - Add error handling for admin users (IllegalStateException)
  - Add error handling for no workgroups (NoSuchElementException)
  - Add SeverityCounts internal data class
  - Add comprehensive logging

**Implementation Steps**:
1. Copy `AccountVulnsService.kt` as starting point
2. Replace AWS account logic with workgroup logic
3. Update data source from UserMapping to Workgroup membership
4. Keep severity calculation logic identical
5. Update sorting: alphabetical by workgroup name (not account ID)
6. Handle assets in multiple workgroups correctly

**Key Logic**:
```kotlin
// 1. Validate user is not admin
if (authentication.roles.contains("ADMIN")) {
    throw IllegalStateException("Admin users should use System Vulns")
}

// 2. Get user's workgroups
val workgroups = workgroupRepository.findWorkgroupsByUserEmail(userEmail)
if (workgroups.isEmpty()) {
    throw NoSuchElementException("No workgroups found")
}

// 3. Get assets for workgroups
val assets = assetRepository.findByWorkgroupIdIn(workgroupIds)

// 4. Calculate severity counts
val severityCountsMap = countVulnerabilitiesBySeverity(assetIds)

// 5. Group assets by workgroup (handle multiple workgroups per asset)
val assetsByWorkgroup = assets.groupBy { asset ->
    asset.workgroups.filter { it.id in workgroupIds }
}.flatMap { (workgroups, assetsInGroup) ->
    workgroups.map { wg -> wg to assetsInGroup }
}.groupBy({ it.first }, { it.second })
.mapValues { it.value.flatten().distinct() }

// 6. Build and return DTO
```

**Files to Create**:
```
src/backendng/src/main/kotlin/com/secman/service/
└── WorkgroupVulnsService.kt (NEW)
```

**Acceptance Criteria**:
- Service compiles without errors
- Admin users are rejected with appropriate error
- Users with no workgroups get appropriate error
- Severity calculation works correctly
- Assets in multiple workgroups appear in all sections
- Workgroups sorted alphabetically
- Assets sorted by vulnerability count (descending)

**Reference**:
- `src/backendng/src/main/kotlin/com/secman/service/AccountVulnsService.kt` (main reference)

---

### 1.4 Create Controller
**Duration**: 2 hours  
**Assignee**: Backend Developer  
**Priority**: P0 (Critical)  
**Dependencies**: Task 1.3

**Tasks**:
- [ ] Create `WorkgroupVulnsController.kt`
  - Add `@Controller("/api/wg-vulns")` annotation
  - Add `@Secured(SecurityRule.IS_AUTHENTICATED)` annotation
  - Add `@ExecuteOn(TaskExecutors.BLOCKING)` annotation
  - Inject `WorkgroupVulnsService`
  - Implement GET endpoint
  - Handle IllegalStateException → 403 with AdminRedirectError
  - Handle NoSuchElementException → 404 with ErrorResponse
  - Handle generic exceptions → 500 with ErrorResponse
  - Add comprehensive logging

- [ ] Add error response DTOs (if not already in controller)
  - ErrorResponse data class
  - AdminRedirectError data class

**Files to Create**:
```
src/backendng/src/main/kotlin/com/secman/controller/
└── WorkgroupVulnsController.kt (NEW)
```

**Acceptance Criteria**:
- Controller compiles without errors
- Endpoint responds to GET /api/wg-vulns
- Authentication is required
- Error responses match API contract
- Logs include useful debugging information

**Reference**:
- `src/backendng/src/main/kotlin/com/secman/controller/AccountVulnsController.kt`

---

### 1.5 Write Backend Unit Tests
**Duration**: 4-6 hours  
**Assignee**: Backend Developer  
**Priority**: P0 (Critical)  
**Dependencies**: Tasks 1.3, 1.4

**Tasks**:
- [ ] Create `WorkgroupVulnsServiceTest.kt`
  - Test: Admin user rejection (expect IllegalStateException)
  - Test: No workgroups (expect NoSuchElementException)
  - Test: Single workgroup with single asset
  - Test: Single workgroup with multiple assets
  - Test: Multiple workgroups
  - Test: Workgroup with no assets
  - Test: Asset in multiple workgroups
  - Test: Severity count calculation
  - Test: Workgroup sorting (alphabetical)
  - Test: Asset sorting (by vulnerability count descending)
  - Test: Global totals calculation
  - Mock all dependencies

- [ ] Create `WorkgroupVulnsControllerTest.kt`
  - Test: Authentication required (401)
  - Test: Admin rejection (403)
  - Test: No workgroups (404)
  - Test: Successful response (200)
  - Test: Generic error (500)
  - Mock service layer

**Files to Create**:
```
src/backendng/src/test/kotlin/com/secman/
├── service/WorkgroupVulnsServiceTest.kt (NEW)
└── controller/WorkgroupVulnsControllerTest.kt (NEW)
```

**Acceptance Criteria**:
- All tests pass
- Code coverage >80% for new code
- Tests are maintainable and well-documented
- Edge cases are covered

**Reference**:
- `src/backendng/src/test/kotlin/com/secman/service/AccountVulnsServiceTest.kt`
- `src/backendng/src/test/kotlin/com/secman/controller/AccountVulnsControllerTest.kt`

---

### 1.6 Write Backend Integration Tests
**Duration**: 3-4 hours  
**Assignee**: Backend Developer  
**Priority**: P1 (High)  
**Dependencies**: Tasks 1.3, 1.4

**Tasks**:
- [ ] Create `WorkgroupVulnsIntegrationTest.kt`
  - Test: End-to-end with real database
  - Test: User with single workgroup
  - Test: User with multiple workgroups
  - Test: Asset in multiple workgroups (verify appears in both)
  - Test: Workgroup membership enforcement
  - Test: Vulnerability count accuracy
  - Test: SQL query performance
  - Use test fixtures for data setup

**Files to Create**:
```
src/backendng/src/test/kotlin/com/secman/integration/
└── WorkgroupVulnsIntegrationTest.kt (NEW)
```

**Acceptance Criteria**:
- All tests pass with real database
- Tests clean up after themselves
- Performance is acceptable
- Data accuracy verified

---

### 1.7 Write Backend Contract Tests
**Duration**: 2 hours  
**Assignee**: Backend Developer  
**Priority**: P1 (High)  
**Dependencies**: Task 1.4

**Tasks**:
- [ ] Create `WgVulnsContractTest.kt`
  - Validate response schema against OpenAPI spec
  - Test all status codes (200, 401, 403, 404, 500)
  - Test response content type
  - Test required fields presence
  - Test optional fields handling

**Files to Create**:
```
src/backendng/src/test/kotlin/com/secman/contract/
└── WgVulnsContractTest.kt (NEW)
```

**Acceptance Criteria**:
- All contract tests pass
- Response matches OpenAPI specification
- All status codes tested

---

## Phase 2: Frontend Foundation (Days 3-4)

### 2.1 Create Frontend API Service
**Duration**: 1-2 hours  
**Assignee**: Frontend Developer  
**Priority**: P0 (Critical)

**Tasks**:
- [ ] Create `workgroupVulnsService.ts`
  - Define TypeScript interfaces: WorkgroupVulnsSummary, WorkgroupGroup
  - Reuse AssetVulnCount interface (import from existing)
  - Implement `getWorkgroupVulns()` function
  - Use axios with proper error handling
  - Handle 403 (admin redirect) specially
  - Handle 404 (no workgroups) specially

**Files to Create**:
```
src/frontend/src/services/
└── workgroupVulnsService.ts (NEW)
```

**Acceptance Criteria**:
- Service compiles without TypeScript errors
- Interfaces match backend DTOs
- Error handling is robust
- axios configuration is correct

**Reference**:
- `src/frontend/src/services/accountVulnsService.ts`

---

### 2.2 Create Astro Page
**Duration**: 30 minutes  
**Assignee**: Frontend Developer  
**Priority**: P0 (Critical)  
**Dependencies**: Task 2.3 (can be done in parallel)

**Tasks**:
- [ ] Create `wg-vulns.astro`
  - Import Layout component
  - Import WorkgroupVulnsView component
  - Load component with `client:load` directive
  - Set page title: "Workgroup Vulnerabilities"

**Files to Create**:
```
src/frontend/src/pages/
└── wg-vulns.astro (NEW)
```

**Acceptance Criteria**:
- Page builds without errors
- Route `/wg-vulns` is accessible
- Component loads correctly

**Reference**:
- `src/frontend/src/pages/account-vulns.astro`

---

### 2.3 Create React Component
**Duration**: 4-6 hours  
**Assignee**: Frontend Developer  
**Priority**: P0 (Critical)  
**Dependencies**: Task 2.1

**Tasks**:
- [ ] Create `WorkgroupVulnsView.tsx`
  - Copy `AccountVulnsView.tsx` as starting point
  - Update state management
  - Update API call to use `getWorkgroupVulns()`
  - Update rendering for workgroup groups (not AWS accounts)
  - Update icons (bi-cloud → bi-people-fill)
  - Update labels and text
  - Reuse AssetVulnTable component
  - Reuse SeverityBadge component
  - Implement loading state
  - Implement admin redirect state
  - Implement error state
  - Implement no workgroups state
  - Implement success state with data

**Component Structure**:
```tsx
WorkgroupVulnsView
├── State: summary, loading, error, isAdminRedirect
├── Effect: fetchWorkgroupVulns() on mount
├── Loading State (spinner + message)
├── Admin Redirect State (warning alert)
├── Error State (danger alert)
├── No Workgroups State (info alert)
└── Success State
    ├── Header with refresh button
    ├── Summary cards (4 columns)
    └── Workgroup groups
        ├── Card header (name, description, badges)
        └── AssetVulnTable
```

**Files to Create**:
```
src/frontend/src/components/
└── WorkgroupVulnsView.tsx (NEW)
```

**Acceptance Criteria**:
- Component compiles without errors
- All states render correctly
- Error handling works
- Data displays correctly
- Refresh button works
- Links navigate correctly
- Responsive design works

**Reference**:
- `src/frontend/src/components/AccountVulnsView.tsx`

---

### 2.4 Update Sidebar Navigation
**Duration**: 2 hours  
**Assignee**: Frontend Developer  
**Priority**: P0 (Critical)  
**Dependencies**: Task 2.1

**Tasks**:
- [ ] Update `Sidebar.tsx`
  - Add state: `hasWorkgroups` boolean
  - Add effect: Check workgroup membership on mount
  - Add menu item in Vuln Management section (after Account vulns)
  - Implement conditional rendering logic
  - Disable for admin users
  - Disable for users with no workgroups
  - Add tooltips for disabled states
  - Use `bi-people-fill` icon

**Implementation**:
```tsx
// Add state
const [hasWorkgroups, setHasWorkgroups] = useState(false);

// Check workgroup membership
useEffect(() => {
    async function checkWorkgroups() {
        if (!isAdmin) {
            try {
                const response = await axios.get('/api/wg-vulns');
                setHasWorkgroups(response.data.workgroupGroups?.length > 0);
            } catch {
                setHasWorkgroups(false);
            }
        }
    }
    checkWorkgroups();
}, [isAdmin]);

// Render menu item
<li>
    <a
        href={isAdmin ? "#" : "/wg-vulns"}
        className={isAdmin || !hasWorkgroups ? 'text-muted' : 'text-dark'}
        title={isAdmin ? "Admins should use System Vulns" : !hasWorkgroups ? "No workgroups" : "View workgroup vulnerabilities"}
        style={isAdmin || !hasWorkgroups ? { cursor: 'not-allowed', pointerEvents: 'none' } : {}}
    >
        <i className="bi bi-people-fill me-2"></i> WG vulns
    </a>
</li>
```

**Files to Modify**:
```
src/frontend/src/components/
└── Sidebar.tsx (UPDATE)
```

**Acceptance Criteria**:
- Menu item appears in correct location
- Conditional rendering works correctly
- Admin users see disabled state
- Users with no workgroups see disabled state
- Tooltips are helpful
- Navigation works for enabled users

**Reference**:
- Existing "Account vulns" menu item in Sidebar.tsx

---

### 2.5 Write Frontend Component Tests
**Duration**: 2-3 hours  
**Assignee**: Frontend Developer / QA Engineer  
**Priority**: P1 (High)  
**Dependencies**: Tasks 2.1, 2.3

**Tasks**:
- [ ] Create component tests for WorkgroupVulnsView
  - Test: Loading state renders
  - Test: Admin redirect renders
  - Test: Error state renders
  - Test: No workgroups state renders
  - Test: Success state with data renders
  - Test: Refresh button calls API
  - Mock API responses

**Files to Create**:
```
src/frontend/src/tests/
└── WorkgroupVulnsView.test.tsx (NEW)
```

**Acceptance Criteria**:
- All tests pass
- Code coverage >80%
- Tests are maintainable

---

### 2.6 Write E2E Tests
**Duration**: 3-4 hours  
**Assignee**: QA Engineer  
**Priority**: P1 (High)  
**Dependencies**: Tasks 2.2, 2.3, 2.4

**Tasks**:
- [ ] Create `wg-vulns.spec.ts` (Playwright)
  - Test: Navigation from sidebar to WG Vulns page
  - Test: Page loads and displays summary cards
  - Test: Workgroup groups render correctly
  - Test: Asset tables render with data
  - Test: Asset links navigate to asset detail
  - Test: Refresh button works
  - Test: Admin redirect flow
  - Test: No workgroups flow
  - Test: Error handling

**Files to Create**:
```
src/frontend/tests/e2e/
└── wg-vulns.spec.ts (NEW)
```

**Acceptance Criteria**:
- All E2E tests pass
- Tests cover main user flows
- Tests are stable (not flaky)

**Reference**:
- `src/frontend/tests/e2e/vuln-role-access.spec.ts`

---

## Phase 3: Integration & Testing (Day 5)

### 3.1 Manual Testing
**Duration**: 3-4 hours  
**Assignee**: QA Engineer + Developer  
**Priority**: P0 (Critical)  
**Dependencies**: All previous tasks

**Test Scenarios**:
- [ ] Non-admin user with single workgroup
  - Verify page loads
  - Verify workgroup displays correctly
  - Verify assets display correctly
  - Verify vulnerability counts are accurate
  - Verify severity badges show correct numbers
  - Verify asset links work

- [ ] Non-admin user with multiple workgroups
  - Verify all workgroups display
  - Verify workgroups sorted alphabetically
  - Verify assets sorted by vulnerability count

- [ ] Non-admin user with asset in multiple workgroups
  - Verify asset appears in all applicable workgroups
  - Verify global totals don't double-count

- [ ] Non-admin user with empty workgroup
  - Verify workgroup displays with 0 assets
  - Verify empty state message

- [ ] Non-admin user with no workgroups
  - Verify 404 state with helpful message

- [ ] Admin user
  - Verify menu item is disabled
  - Verify direct access redirects to System Vulns

- [ ] Cross-browser testing
  - Chrome
  - Firefox
  - Safari
  - Edge

- [ ] Responsive design testing
  - Desktop (1920x1080)
  - Tablet (768x1024)
  - Mobile (375x667)

**Acceptance Criteria**:
- All scenarios pass
- No visual bugs
- No console errors
- Performance is acceptable

---

### 3.2 Performance Testing
**Duration**: 2 hours  
**Assignee**: Backend Developer  
**Priority**: P1 (High)  
**Dependencies**: All previous tasks

**Tests**:
- [ ] Load time with typical data (5 workgroups, 100 assets)
  - Target: <2 seconds
  - Measure backend response time
  - Measure frontend render time

- [ ] Load time with large data (20 workgroups, 1000 assets)
  - Target: <5 seconds
  - Identify bottlenecks

- [ ] Database query performance
  - Check query execution plans
  - Verify indexes are used
  - Optimize if needed

- [ ] Concurrent users (10 simultaneous requests)
  - Verify no errors
  - Verify reasonable response times

**Acceptance Criteria**:
- Performance targets met
- No N+1 query issues
- Database queries are efficient

---

### 3.3 Security Testing
**Duration**: 2 hours  
**Assignee**: Tech Lead / Security Engineer  
**Priority**: P0 (Critical)  
**Dependencies**: All previous tasks

**Tests**:
- [ ] Access control verification
  - Verify non-admin can access
  - Verify admin is rejected
  - Verify unauthenticated is rejected
  - Verify user can only see their workgroups

- [ ] Data leakage testing
  - Verify no exposure of other users' workgroups
  - Verify no exposure of assets outside workgroups

- [ ] SQL injection testing
  - Test with malicious input
  - Verify parameterized queries protect

- [ ] XSS testing
  - Test with script tags in workgroup names
  - Verify proper escaping

**Acceptance Criteria**:
- No security vulnerabilities found
- Access control is bulletproof
- No data leakage possible

---

### 3.4 Bug Fixes
**Duration**: 2-4 hours (buffer)  
**Assignee**: Developers  
**Priority**: P0 (Critical)  
**Dependencies**: Tasks 3.1, 3.2, 3.3

**Tasks**:
- [ ] Fix bugs found during testing
- [ ] Re-test fixed bugs
- [ ] Update tests if needed

**Acceptance Criteria**:
- All critical bugs fixed
- All tests pass
- No regressions introduced

---

## Phase 4: Documentation & Deployment (Day 6)

### 4.1 Update Documentation
**Duration**: 2-3 hours  
**Assignee**: Backend Developer + Frontend Developer  
**Priority**: P1 (High)  
**Dependencies**: All previous tasks

**Tasks**:
- [ ] Update API documentation
  - Document `/api/wg-vulns` endpoint
  - Add OpenAPI spec to docs
  - Add examples

- [ ] Update user guide
  - Add "WG Vulns" section
  - Add screenshots
  - Explain how to use feature

- [ ] Update developer documentation
  - Document new service and controller patterns
  - Document repository methods
  - Add code examples

- [ ] Update HISTORY.md
  - Add feature entry with version
  - Describe what was added

- [ ] Update README.md (if applicable)
  - Add feature to feature list

**Files to Update**:
```
docs/
├── api/endpoints.md (UPDATE)
├── user-guide/vulnerabilities.md (UPDATE)
└── developer-guide/backend-patterns.md (UPDATE)
HISTORY.md (UPDATE)
README.md (UPDATE - optional)
```

**Acceptance Criteria**:
- Documentation is clear and accurate
- Screenshots are included
- Examples are correct

---

### 4.2 Code Review
**Duration**: 2-3 hours  
**Assignee**: Tech Lead  
**Priority**: P0 (Critical)  
**Dependencies**: All development tasks

**Review Checklist**:
- [ ] Code quality
  - Follows project coding standards
  - Proper error handling
  - Appropriate logging
  - No code smells

- [ ] Test coverage
  - All critical paths tested
  - Coverage >80%
  - Tests are maintainable

- [ ] Performance
  - No obvious performance issues
  - Database queries are efficient
  - Frontend renders efficiently

- [ ] Security
  - Access control is correct
  - No security vulnerabilities
  - Input validation present

- [ ] Documentation
  - Code is well-documented
  - Complex logic explained
  - KDoc/JSDoc present

**Acceptance Criteria**:
- Code review approved
- All feedback addressed
- No blocking issues

---

### 4.3 Pre-Deployment Checklist
**Duration**: 1 hour  
**Assignee**: Tech Lead + DevOps  
**Priority**: P0 (Critical)  
**Dependencies**: Task 4.2

**Checklist**:
- [ ] All tests passing (unit, integration, E2E)
- [ ] Code review completed and approved
- [ ] Documentation updated
- [ ] Performance validated (<2s load time)
- [ ] Security reviewed (no vulnerabilities)
- [ ] Database migrations reviewed (none needed for this feature)
- [ ] Feature flag configured (if using feature flags)
- [ ] Rollback plan documented
- [ ] Monitoring/alerts configured
- [ ] Team notified of deployment

**Acceptance Criteria**:
- All checklist items completed
- Team is ready for deployment

---

### 4.4 Deployment
**Duration**: 1-2 hours  
**Assignee**: DevOps + Developer  
**Priority**: P0 (Critical)  
**Dependencies**: Task 4.3

**Deployment Steps**:
1. [ ] Deploy backend to staging
   - Run build
   - Deploy artifact
   - Smoke test

2. [ ] Deploy frontend to staging
   - Run build
   - Deploy static files
   - Clear CDN cache
   - Smoke test

3. [ ] Staging verification
   - Test main user flows
   - Verify no errors in logs
   - Check performance

4. [ ] Deploy backend to production
   - Run build
   - Deploy artifact
   - Monitor logs

5. [ ] Deploy frontend to production
   - Run build
   - Deploy static files
   - Clear CDN cache
   - Monitor logs

6. [ ] Production verification
   - Smoke test
   - Monitor error rates
   - Monitor performance
   - Check user feedback

**Rollback Plan**:
- If critical issues found:
  1. Hide sidebar menu item (quick fix)
  2. Revert backend deployment if needed
  3. Revert frontend deployment if needed
  4. No database rollback needed (no schema changes)

**Acceptance Criteria**:
- Deployment successful
- No errors in production logs
- Feature works as expected
- Performance is acceptable

---

### 4.5 Post-Deployment Monitoring
**Duration**: Ongoing (24-48 hours)  
**Assignee**: Developer + DevOps  
**Priority**: P0 (Critical)  
**Dependencies**: Task 4.4

**Monitoring Tasks**:
- [ ] Monitor error logs for first 24 hours
  - Check for 500 errors
  - Check for unexpected exceptions
  - Check for performance issues

- [ ] Monitor user adoption
  - Track page views
  - Track unique users
  - Track error rates

- [ ] Gather user feedback
  - Check support tickets
  - Monitor internal channels
  - Collect feature requests

- [ ] Performance monitoring
  - Track API response times
  - Track page load times
  - Track database query performance

**Acceptance Criteria**:
- No critical issues found
- Error rate <0.1%
- Performance targets met
- User feedback is positive

---

## 📊 Task Summary

### By Phase
| Phase | Tasks | Duration | Priority |
|-------|-------|----------|----------|
| Phase 1: Backend | 7 tasks | 2-3 days | P0 |
| Phase 2: Frontend | 6 tasks | 2-3 days | P0 |
| Phase 3: Testing | 4 tasks | 1 day | P0 |
| Phase 4: Deployment | 5 tasks | 1 day | P0 |
| **Total** | **22 tasks** | **5-6 days** | - |

### By Priority
- **P0 (Critical)**: 17 tasks
- **P1 (High)**: 5 tasks

### By Assignee
- **Backend Developer**: 7 tasks (2-3 days)
- **Frontend Developer**: 6 tasks (2-3 days)
- **QA Engineer**: 3 tasks (1-2 days, parallel)
- **Tech Lead**: 2 tasks (0.5-1 day, parallel)
- **DevOps**: 1 task (0.5 day, parallel)

## 🎯 Success Metrics

### Technical Metrics
- ✅ Page load time <2 seconds (95th percentile)
- ✅ Error rate <0.1%
- ✅ Code coverage >80%
- ✅ All tests passing
- ✅ Zero security vulnerabilities

### User Adoption
- ✅ 60% of users with workgroups use feature within 1 month
- ✅ Average 5+ visits per active user per week
- ✅ User satisfaction rating 4+ stars

### Quality Metrics
- ✅ Zero critical bugs in production
- ✅ <5 minor bugs in first week
- ✅ 95%+ test pass rate

## 🚧 Risks & Mitigation

### High Priority Risks

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| Performance issues with many workgroups | Medium | High | Load test early, optimize queries, add pagination if needed |
| Asset duplication confusion | Medium | Low | Clear documentation, expected behavior |
| Lazy loading exceptions | Low | Medium | Use JOIN FETCH, test thoroughly |
| Workgroup membership caching issues | Low | Medium | Refresh on page load, add cache invalidation |

### Medium Priority Risks

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| UI inconsistency with Account Vulns | Low | Low | Reuse components, follow same patterns |
| TypeScript/Kotlin type mismatches | Low | Medium | Generate types from backend, validate early |
| Browser compatibility issues | Low | Low | Cross-browser testing, use standard APIs |

## 📝 Notes

### Development Guidelines
- Copy from Account Vulns where possible (80% similar)
- Test early and often
- Document as you go
- Communicate blockers immediately
- Keep PRs small and focused

### Testing Guidelines
- Write tests before fixing bugs
- Test edge cases thoroughly
- Mock external dependencies
- Use realistic test data

### Communication Guidelines
- Daily standup updates
- Slack for quick questions
- PR reviews within 4 hours
- Escalate blockers immediately

## 🔗 References

### Specifications
- [PLAN.md](./PLAN.md) - Detailed technical design
- [QUICKSTART.md](./QUICKSTART.md) - Developer guide
- [UI_MOCKUPS.md](./UI_MOCKUPS.md) - UI specifications
- [contracts/wg-vulns-api.yaml](./contracts/wg-vulns-api.yaml) - API contract

### Reference Implementations
- Backend: `AccountVulnsService.kt`, `AccountVulnsController.kt`
- Frontend: `AccountVulnsView.tsx`, `accountVulnsService.ts`
- Tests: `AccountVulnsServiceTest.kt`, `AccountVulnsContractTest.kt`

### Related Features
- Feature 008: Workgroup-Based Access Control
- Feature 018: Account Vulns view
- Feature 019: Severity breakdown

## ✅ Definition of Done

Feature is complete when:
- ✅ All 22 tasks completed
- ✅ All tests passing (unit, integration, E2E)
- ✅ Code review approved
- ✅ Documentation updated
- ✅ Performance targets met
- ✅ Security review passed
- ✅ Deployed to production
- ✅ Monitoring in place
- ✅ No critical bugs in first 48 hours
- ✅ User feedback collected

---

**Plan Status**: ✅ Ready for Execution  
**Next Steps**: Assign tasks, create tickets, begin Phase 1  
**Questions?**: Contact Tech Lead or review specification documents
