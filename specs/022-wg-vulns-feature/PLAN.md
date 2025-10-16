# Feature 022: WG Vulns - Workgroup-Based Vulnerability View

## Overview
Add a new "WG Vulns" menu item under Vuln Management that displays vulnerabilities grouped by workgroups for users who are members of one or more workgroups. This feature mirrors the Account Vulns UI pattern but groups by workgroups and their assets instead of AWS accounts.

## User Story
**As a** non-admin user who is a member of one or more workgroups  
**I want to** view vulnerabilities organized by my workgroups and their assets  
**So that** I can manage security issues for resources within my team's scope

## Key Requirements

### Functional Requirements

#### FR-001: Access Control
- **Must** be available only to authenticated users who are members of at least one workgroup
- **Must** reject admin users with a redirect to System Vulns (consistent with Account Vulns behavior)
- **Must** show 404/empty state if user has no workgroup memberships
- **Must not** show vulnerabilities for assets in workgroups the user is not a member of

#### FR-002: Data Grouping
- **Must** group assets by workgroups (similar to how Account Vulns groups by AWS Account)
- **Must** display all workgroups the user is a member of (including those with zero assets)
- **Must** sort workgroups alphabetically by name
- **Must** sort assets within each workgroup by vulnerability count (descending)

#### FR-003: Vulnerability Counts
- **Must** show total vulnerability count for each asset
- **Must** show severity breakdown (Critical, High, Medium) for each asset
- **Must** show aggregated severity counts at workgroup level
- **Must** show global severity totals across all user's workgroups

#### FR-004: Navigation
- **Must** add "WG Vulns" menu item under Vuln Management in sidebar
- **Must** be visually similar to "Account vulns" menu item
- **Must** be conditionally displayed (only for users with workgroup memberships)
- **Must** link to `/wg-vulns` page

#### FR-005: UI Consistency
- **Must** follow the same UI pattern as Account Vulns view
- **Must** display summary cards (workgroups count, total assets, total vulns, severity breakdown)
- **Must** use expandable/collapsible card layout for each workgroup
- **Must** include refresh button
- **Must** include asset detail links (clicking asset navigates to asset detail page)

### Non-Functional Requirements

#### NFR-001: Performance
- **Should** use efficient SQL queries with JOINs to minimize database calls
- **Should** calculate severity counts in single query (similar to AccountVulnsService)
- **Should** load in under 2 seconds for users with up to 10 workgroups and 1000 assets

#### NFR-002: Security
- **Must** enforce workgroup membership at database query level
- **Must not** leak information about workgroups user is not a member of
- **Must** validate user authentication on every request

#### NFR-003: Maintainability
- **Should** reuse existing DTOs where possible (AssetVulnCountDto)
- **Should** follow established patterns from Account Vulns implementation
- **Should** include comprehensive unit and integration tests

## Technical Design

### Backend Architecture

#### 1. New DTOs

**File: `src/backendng/src/main/kotlin/com/secman/dto/WorkgroupVulnsSummaryDto.kt`**

Top-level response for WG Vulns view, similar structure to AccountVulnsSummaryDto but for workgroups.

```kotlin
package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class WorkgroupVulnsSummaryDto(
    val workgroupGroups: List<WorkgroupGroupDto>,
    val totalAssets: Int,
    val totalVulnerabilities: Int,
    val globalCritical: Int? = null,
    val globalHigh: Int? = null,
    val globalMedium: Int? = null
)
```

**File: `src/backendng/src/main/kotlin/com/secman/dto/WorkgroupGroupDto.kt`**

Represents a single workgroup with its assets and vulnerability counts, similar to AccountGroupDto.

```kotlin
package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class WorkgroupGroupDto(
    val workgroupId: Long,
    val workgroupName: String,
    val workgroupDescription: String? = null,
    val assets: List<AssetVulnCountDto>,  // Reuse existing DTO
    val totalAssets: Int,
    val totalVulnerabilities: Int,
    val totalCritical: Int? = null,
    val totalHigh: Int? = null,
    val totalMedium: Int? = null
)
```

#### 2. New Service

**File: `src/backendng/src/main/kotlin/com/secman/service/WorkgroupVulnsService.kt`**

Service for WG Vulns feature - provides vulnerability summaries grouped by user's workgroups. Key responsibilities:

- Validate user authentication and reject admin users
- Query user's workgroup memberships
- Fetch assets for user's workgroups
- Calculate vulnerability counts by severity (reusing pattern from AccountVulnsService)
- Group and sort data appropriately
- Return structured DTO

Key method signature:
```kotlin
fun getWorkgroupVulnsSummary(authentication: Authentication): WorkgroupVulnsSummaryDto
```

Throws:
- `IllegalStateException` if user has ADMIN role
- `NoSuchElementException` if user has no workgroup memberships

#### 3. New Controller

**File: `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupVulnsController.kt`**

REST controller for WG Vulns feature.

Endpoint: `GET /api/wg-vulns`

Status Codes:
- `200 OK`: Successfully retrieved workgroup vulnerability overview
- `401 Unauthorized`: Missing or invalid authentication token
- `403 Forbidden`: Admin users (should use System Vulns instead)
- `404 Not Found`: User has no workgroup memberships
- `500 Internal Server Error`: Unexpected error

#### 4. Repository Updates

**WorkgroupRepository additions:**

```kotlin
/**
 * Find all workgroups that a user is a member of
 * Uses JOIN with user_workgroups table
 */
fun findWorkgroupsByUserEmail(email: String): List<Workgroup>
```

**AssetRepository additions:**

```kotlin
/**
 * Find all assets that belong to any of the specified workgroups
 * Uses JOIN with asset_workgroups table
 */
fun findByWorkgroupIdIn(workgroupIds: List<Long>): List<Asset>
```

### Frontend Architecture

#### 1. New Page

**File: `src/frontend/src/pages/wg-vulns.astro`**

Simple Astro page that loads the WorkgroupVulnsView React component.

#### 2. New React Component

**File: `src/frontend/src/components/WorkgroupVulnsView.tsx`**

Main view component for WG Vulns feature. Displays vulnerabilities grouped by workgroups with:

- Loading state with spinner
- Admin redirect handling
- Error handling
- No workgroups state
- Summary statistics cards
- Workgroup groups with collapsible sections
- Asset tables (reusing AssetVulnTable component)
- Severity badges (reusing SeverityBadge component)

State management:
- `summary`: WorkgroupVulnsSummary data from API
- `loading`: Loading state boolean
- `error`: Error message string
- `isAdminRedirect`: Admin redirect flag

#### 3. New Service

**File: `src/frontend/src/services/workgroupVulnsService.ts`**

Frontend service for API communication.

Main function:
```typescript
export async function getWorkgroupVulns(): Promise<WorkgroupVulnsSummary>
```

TypeScript interfaces:
- `WorkgroupVulnsSummary`: Top-level response
- `WorkgroupGroup`: Single workgroup data
- `AssetVulnCount`: Asset with vulnerability counts (reused from Account Vulns)

#### 4. Sidebar Navigation Update

**File: `src/frontend/src/components/Sidebar.tsx`**

Add new menu item under Vuln Management section:

- Check if user has workgroup memberships
- Conditionally display "WG vulns" menu item
- Disable for admin users
- Disable for users with no workgroups
- Add appropriate tooltips

## Implementation Strategy

### Phase 1: Backend Foundation (Day 1-2)

1. Create DTOs (`WorkgroupVulnsSummaryDto`, `WorkgroupGroupDto`)
2. Add repository methods (`findWorkgroupsByUserEmail`, `findByWorkgroupIdIn`)
3. Create `WorkgroupVulnsService` with core logic
4. Write service unit tests

### Phase 2: Backend API (Day 2-3)

1. Create `WorkgroupVulnsController`
2. Write controller unit tests
3. Write integration tests
4. Write contract tests (OpenAPI spec)

### Phase 3: Frontend Foundation (Day 3-4)

1. Create `workgroupVulnsService.ts`
2. Create `wg-vulns.astro` page
3. Create `WorkgroupVulnsView.tsx` component
4. Write service unit tests

### Phase 4: Frontend Integration (Day 4-5)

1. Update `Sidebar.tsx` with new menu item
2. Add workgroup membership check logic
3. Write component tests
4. Write E2E tests with Playwright

### Phase 5: Testing & Polish (Day 5-6)

1. Manual testing across different user scenarios
2. Performance testing
3. Bug fixes
4. Documentation updates

## Testing Strategy

### Backend Unit Tests

1. **WorkgroupVulnsServiceTest.kt**
   - Admin rejection (IllegalStateException)
   - No workgroups (NoSuchElementException)
   - Single workgroup with assets
   - Multiple workgroups
   - Workgroup with no assets
   - Severity count aggregation
   - Workgroup sorting (alphabetical)
   - Asset sorting (by vulnerability count descending)

2. **WorkgroupVulnsControllerTest.kt**
   - Authentication requirement (401)
   - Admin redirect (403)
   - No workgroups (404)
   - Successful response (200)
   - Error handling (500)

### Frontend Unit Tests

1. **workgroupVulnsService.test.ts**
   - Successful API call
   - Error response handling
   - Network error handling

2. **WorkgroupVulnsView.test.tsx**
   - Loading state rendering
   - Admin redirect rendering
   - Error state rendering
   - No workgroups state rendering
   - Successful data rendering
   - Refresh functionality

### Integration Tests

1. **WorkgroupVulnsIntegrationTest.kt**
   - End-to-end with real database
   - User with multiple workgroups
   - Asset in multiple workgroups
   - Workgroup membership enforcement
   - Vulnerability count accuracy

### E2E Tests

1. **wg-vulns.spec.ts** (Playwright)
   - Navigation from sidebar
   - UI rendering with data
   - Workgroup grouping display
   - Asset links functionality
   - Refresh button
   - Admin redirect flow
   - No workgroups flow

## Edge Cases & Considerations

### 1. Asset in Multiple Workgroups
**Scenario**: An asset belongs to multiple workgroups that a user is a member of.

**Solution**: Asset appears in ALL applicable workgroup groups. Vulnerability counts are NOT duplicated in global totals (count each vulnerability only once).

**Implementation**: Use `distinct()` when collecting assets from multiple workgroups.

### 2. Workgroups with No Assets
**Scenario**: User is a member of a workgroup that has no assets assigned.

**Solution**: Display the workgroup with 0 assets, consistent with Account Vulns behavior.

**Implementation**: Map all user workgroups, defaulting to empty asset list.

### 3. Performance with Many Workgroups
**Scenario**: User is a member of 50+ workgroups with thousands of assets.

**Solution**: Use efficient SQL with proper JOINs and indexes. Consider pagination in future enhancement.

**Implementation**: Single query with JOINs, batch severity count calculation.

### 4. Concurrent Workgroup Changes
**Scenario**: Admin removes user from workgroup while user is viewing WG Vulns.

**Solution**: No real-time updates required. User sees stale data until refresh.

**Implementation**: No special handling needed; refresh button allows user to get latest data.

### 5. Workgroup Renamed
**Scenario**: Admin renames a workgroup while user is viewing it.

**Solution**: User sees old name until refresh.

**Implementation**: No special handling needed.

## Security Considerations

1. **Authentication**: JWT validation on every request
2. **Authorization**: Enforce workgroup membership at query level
3. **SQL Injection**: Use parameterized queries (Micronaut Data handles this)
4. **Information Disclosure**: Never expose workgroups user is not a member of
5. **Admin Bypass**: Explicitly reject admin users with clear error message

## Performance Targets

- Page load time: < 2 seconds for typical user (5 workgroups, 100 assets)
- Database queries: Maximum 3 queries per request
  1. Get user's workgroups
  2. Get assets for workgroups
  3. Get vulnerability counts by severity
- Memory usage: < 100MB for response serialization
- Concurrent users: Support 100+ simultaneous requests

## Migration & Deployment

### Database Changes
**None required** - using existing tables and relationships:
- `workgroup` table
- `user_workgroups` join table
- `asset_workgroups` join table
- `asset` table
- `vulnerability` table

### API Changes
**New endpoint only** - no breaking changes to existing APIs.

### Deployment Steps
1. Deploy backend changes (new endpoint, no migration needed)
2. Deploy frontend changes (new page and menu item)
3. Clear CDN cache if applicable
4. Monitor error logs for first 24 hours
5. Gather user feedback

### Rollback Plan
1. Remove menu item from Sidebar (users can't access page)
2. Optionally remove backend endpoint (or leave it, harmless)
3. No database rollback needed

## Success Metrics

### Adoption Metrics
- 60% of users with workgroup memberships use feature within 1 month
- Average 5+ visits per active user per week

### Performance Metrics
- 95th percentile load time < 2 seconds
- Error rate < 0.1%
- Zero unauthorized access incidents

### User Satisfaction
- User feedback rating: 4+ stars average
- Feature request alignment: Addresses top 3 pain points

## Future Enhancements (Out of Scope for v1)

1. **Export Functionality**: CSV/PDF export of workgroup vulnerabilities
2. **Filtering**: Filter by severity, asset type, date range
3. **Search**: Search assets by name within workgroups
4. **Real-time Updates**: WebSocket updates for live vulnerability changes
5. **Pagination**: Paginate workgroup list for users with many workgroups
6. **Customizable Sorting**: Allow users to change sort order (by name, by vuln count, etc.)
7. **Inline Vulnerability Details**: Expand to show vulnerability details without navigation
8. **Dashboard Widget**: Add WG Vulns summary to main dashboard
9. **Email Notifications**: Alert users when critical vulnerabilities appear in their workgroups
10. **Comparison View**: Compare vulnerability counts across different time periods

## Documentation Updates Required

1. **User Guide**: Add section on WG Vulns feature
2. **API Documentation**: Document `/api/wg-vulns` endpoint
3. **Developer Guide**: Document new service and controller patterns
4. **HISTORY.md**: Add feature entry
5. **README.md**: Update feature list if applicable

## Open Questions

1. **Q**: Should assets appear in multiple workgroup sections if they belong to multiple workgroups?
   **A**: Yes, for clarity and completeness. User should see asset in all their relevant contexts.

2. **Q**: Should global totals deduplicate assets that appear in multiple workgroups?
   **A**: Yes, each unique asset and vulnerability should be counted only once in global totals.

3. **Q**: Should admins be able to view WG Vulns if they are also workgroup members?
   **A**: No, maintain consistency with Account Vulns. Admins should use System Vulns.

4. **Q**: What icon should be used for WG Vulns menu item?
   **A**: Use `bi-people-fill` (Bootstrap Icon) to represent workgroups/teams.

5. **Q**: Should we show workgroups with 0 assets?
   **A**: Yes, consistent with Account Vulns showing AWS accounts with 0 assets.
