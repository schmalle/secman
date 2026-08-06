# WG Vulns - Developer Quick Start

This guide helps developers quickly understand and implement the WG Vulns feature.

## 🎯 What You're Building

A new view under "Vuln Management" that shows vulnerabilities grouped by workgroups (instead of AWS accounts like Account Vulns).

**Key Point**: This is essentially a clone of Account Vulns but with workgroup grouping instead of AWS account grouping.

## 📋 Prerequisites

Before starting, ensure you're familiar with:
- ✅ Account Vulns feature (Feature 018/019) - your reference implementation
- ✅ Workgroup system (Feature 008) - the foundation
- ✅ Kotlin + Micronaut backend patterns
- ✅ React + TypeScript frontend patterns
- ✅ Bootstrap 5 UI components

## 🚀 Implementation Checklist

### Backend (2-3 days)

#### Step 1: Create DTOs
```kotlin
// src/backendng/src/main/kotlin/com/secman/dto/

✅ WorkgroupVulnsSummaryDto.kt
   - Top-level response DTO
   - Similar to AccountVulnsSummaryDto
   - Contains: workgroupGroups, totalAssets, totalVulnerabilities, global severity counts

✅ WorkgroupGroupDto.kt
   - Single workgroup DTO
   - Similar to AccountGroupDto
   - Contains: workgroupId, workgroupName, assets, totals, severity counts
   - Reuses: AssetVulnCountDto (existing)
```

#### Step 2: Update Repositories
```kotlin
// src/backendng/src/main/kotlin/com/secman/repository/

✅ WorkgroupRepository.kt - Add method:
   fun findWorkgroupsByUserEmail(email: String): List<Workgroup>

✅ AssetRepository.kt - Add method:
   fun findByWorkgroupIdIn(workgroupIds: List<Long>): List<Asset>
```

#### Step 3: Create Service
```kotlin
// src/backendng/src/main/kotlin/com/secman/service/WorkgroupVulnsService.kt

✅ Main method: getWorkgroupVulnsSummary(authentication: Authentication)
✅ Helper method: countVulnerabilitiesBySeverity(assetIds: List<Long>)
   → Copy from AccountVulnsService - same logic!

Key logic flow:
1. Extract user email, validate not admin
2. Get user's workgroups via repository
3. Get assets for those workgroups
4. Calculate severity counts (SQL query)
5. Group assets by workgroup
6. Sort: workgroups alphabetically, assets by vuln count desc
7. Build and return DTO
```

#### Step 4: Create Controller
```kotlin
// src/backendng/src/main/kotlin/com/secman/controller/WorkgroupVulnsController.kt

✅ Endpoint: GET /api/wg-vulns
✅ Security: @Secured(SecurityRule.IS_AUTHENTICATED)
✅ Returns: WorkgroupVulnsSummaryDto
✅ Error handling:
   - 403: Admin users (IllegalStateException)
   - 404: No workgroups (NoSuchElementException)
   - 500: Other exceptions
```

#### Step 5: Write Tests
```kotlin
// src/backendng/src/test/kotlin/com/secman/

✅ service/WorkgroupVulnsServiceTest.kt
   - Test admin rejection
   - Test no workgroups
   - Test single workgroup
   - Test multiple workgroups
   - Test severity aggregation
   - Test sorting

✅ controller/WorkgroupVulnsControllerTest.kt
   - Test authentication
   - Test authorization
   - Test error responses

✅ integration/WorkgroupVulnsIntegrationTest.kt
   - End-to-end with database

✅ contract/WgVulnsContractTest.kt
   - Validate against OpenAPI spec
```

### Frontend (2-3 days)

#### Step 1: Create API Service
```typescript
// src/frontend/src/services/workgroupVulnsService.ts

✅ Interface: WorkgroupVulnsSummary
✅ Interface: WorkgroupGroup
✅ Function: getWorkgroupVulns(): Promise<WorkgroupVulnsSummary>
   → Copy pattern from accountVulnsService.ts
```

#### Step 2: Create Page
```typescript
// src/frontend/src/pages/wg-vulns.astro

✅ Simple Astro page
✅ Import Layout and WorkgroupVulnsView
✅ Load component with client:load
```

#### Step 3: Create Component
```typescript
// src/frontend/src/components/WorkgroupVulnsView.tsx

✅ Main component - React functional component
✅ State: summary, loading, error, isAdminRedirect
✅ Effect: Call API on mount
✅ Render states:
   - Loading: Spinner + message
   - Admin redirect: Warning alert + buttons
   - Error: Danger alert + retry button
   - No workgroups: Info alert + message
   - Success: Summary cards + workgroup groups

✅ Reuse components:
   - AssetVulnTable (for asset tables)
   - SeverityBadge (for severity counts)

⚠️ Pro tip: Copy AccountVulnsView.tsx and do find/replace:
   - "Account" → "Workgroup"
   - "AWS Account" → "Workgroup"
   - "awsAccountId" → "workgroupId"
   - "bi-cloud" → "bi-people-fill"
```

#### Step 4: Update Sidebar
```typescript
// src/frontend/src/components/Sidebar.tsx

✅ Add state: hasWorkgroups
✅ Add effect: Check workgroup membership
✅ Add menu item in Vuln Management section:
   <li>
     <a href="/wg-vulns">
       <i className="bi bi-people-fill me-2"></i> WG vulns
     </a>
   </li>
✅ Conditional rendering: Show only if user has workgroups and not admin
```

#### Step 5: Write Tests
```typescript
// src/frontend/src/tests/

✅ e2e/wg-vulns.spec.ts (Playwright)
   - Test navigation
   - Test UI rendering
   - Test error states
   - Test refresh button
```

## 🔑 Key Implementation Tips

### 1. Copy from Account Vulns
Most of the code is similar to Account Vulns. Start by copying and adapting:

**Backend:**
- `AccountVulnsService.kt` → `WorkgroupVulnsService.kt`
- `AccountVulnsController.kt` → `WorkgroupVulnsController.kt`
- Keep: `AssetVulnCountDto.kt` (reuse as-is)

**Frontend:**
- `AccountVulnsView.tsx` → `WorkgroupVulnsView.tsx`
- `accountVulnsService.ts` → `workgroupVulnsService.ts`
- Reuse: `AssetVulnTable.tsx`, `SeverityBadge.tsx`

### 2. Main Differences from Account Vulns

| Aspect | Account Vulns | WG Vulns |
|--------|--------------|----------|
| **Data source** | UserMapping table | Workgroup membership |
| **Grouping field** | AWS Account ID | Workgroup ID + Name |
| **Repository query** | `findDistinctAwsAccountIdByEmail()` | `findWorkgroupsByUserEmail()` |
| **Asset query** | `findByCloudAccountIdIn()` | `findByWorkgroupIdIn()` |
| **Sorting** | By AWS Account ID | By Workgroup Name |
| **Group identifier** | String (account ID) | Long (workgroup ID) + String (name) |
| **Icon** | `bi-cloud` | `bi-people-fill` |

### 3. Asset in Multiple Workgroups
An asset can belong to multiple workgroups. Handle this correctly:

```kotlin
// Backend: Group assets by workgroup
val assetsByWorkgroup = assets.groupBy { asset ->
    asset.workgroups.filter { it.id in workgroupIds }
}.flatMap { (workgroups, assetsInGroup) ->
    workgroups.map { wg -> wg to assetsInGroup }
}.groupBy({ it.first }, { it.second })
.mapValues { it.value.flatten().distinct() }
```

**Result**: Asset appears in ALL applicable workgroup sections.

### 4. Severity Calculation
Reuse the exact same SQL query from `AccountVulnsService`:

```kotlin
private fun countVulnerabilitiesBySeverity(assetIds: List<Long>): Map<Long, SeverityCounts> {
    if (assetIds.isEmpty()) return emptyMap()
    
    val sql = """
        SELECT 
            v.asset_id,
            COUNT(*) as total_count,
            SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'CRITICAL' THEN 1 ELSE 0 END) as critical_count,
            SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'HIGH' THEN 1 ELSE 0 END) as high_count,
            SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'MEDIUM' THEN 1 ELSE 0 END) as medium_count,
            SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'LOW' THEN 1 ELSE 0 END) as low_count,
            SUM(CASE WHEN COALESCE(v.cvss_severity, '') = '' 
                     OR UPPER(v.cvss_severity) NOT IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW') 
                THEN 1 ELSE 0 END) as unknown_count
        FROM vulnerability v
        WHERE v.asset_id IN (:assetIds)
        GROUP BY v.asset_id
    """.trimIndent()
    
    // Execute and map results...
}
```

### 5. Access Control Pattern
Follow the same pattern as Account Vulns:

```kotlin
// 1. Extract email
val userEmail = authentication.attributes["email"]?.toString()
    ?: throw IllegalStateException("Email not found")

// 2. Check admin role
if (authentication.roles.contains("ADMIN")) {
    throw IllegalStateException("Admin users should use System Vulns")
}

// 3. Check workgroup membership
val workgroups = workgroupRepository.findWorkgroupsByUserEmail(userEmail)
if (workgroups.isEmpty()) {
    throw NoSuchElementException("No workgroups found")
}
```

## 🧪 Testing Strategy

### Unit Tests - Must Cover
- ✅ Admin rejection (IllegalStateException thrown)
- ✅ No workgroups (NoSuchElementException thrown)
- ✅ Single workgroup with assets
- ✅ Multiple workgroups
- ✅ Workgroup with no assets
- ✅ Asset in multiple workgroups
- ✅ Severity count calculation
- ✅ Sorting (workgroups alphabetical, assets by count desc)

### Integration Tests - Must Cover
- ✅ End-to-end API call with database
- ✅ Correct SQL joins
- ✅ Data accuracy
- ✅ Workgroup membership enforcement

### E2E Tests - Must Cover
- ✅ Navigation from sidebar
- ✅ Page renders correctly
- ✅ Admin redirect flow
- ✅ No workgroups flow
- ✅ Refresh button works

## 🐛 Common Pitfalls

### 1. Lazy Loading Issues
**Problem**: `asset.workgroups` causes LazyInitializationException

**Solution**: Use JOIN FETCH in repository query or ensure transaction context:
```kotlin
@Query("SELECT a FROM Asset a JOIN FETCH a.workgroups w WHERE w.id IN :workgroupIds")
fun findByWorkgroupIdIn(workgroupIds: List<Long>): List<Asset>
```

### 2. Duplicate Counts
**Problem**: Asset in 2 workgroups counted twice in global totals

**Solution**: Use `distinct()` when collecting assets:
```kotlin
val allAssets = workgroupGroups.flatMap { it.assets }.distinctBy { it.id }
```

### 3. Null Severity Fields
**Problem**: Old vulnerabilities have null severity

**Solution**: Use `COALESCE` in SQL and nullable fields in DTO:
```kotlin
criticalCount: Int? = null  // nullable with default
```

### 4. Frontend Type Mismatches
**Problem**: TypeScript interface doesn't match backend DTO

**Solution**: Keep interfaces in sync. Optional fields must be marked with `?`:
```typescript
globalCritical?: number;  // matches Kotlin: Int? = null
```

## 📊 Performance Considerations

### Database Queries
Aim for **3 queries maximum** per request:
1. Get user's workgroups (with JOIN to user_workgroups)
2. Get assets for workgroups (with JOIN to asset_workgroups)
3. Get vulnerability counts (single aggregation query)

### Query Optimization
```sql
-- Ensure indexes exist:
CREATE INDEX idx_user_workgroups_email ON user_workgroups(user_email);
CREATE INDEX idx_asset_workgroups_workgroup ON asset_workgroups(workgroup_id);
CREATE INDEX idx_vulnerability_asset_severity ON vulnerability(asset_id, cvss_severity);
```

### Memory Usage
For 10 workgroups × 100 assets = 1000 assets with 100k vulnerabilities:
- Expected response size: ~500KB JSON
- Memory usage: ~50MB during processing
- Response time: <2 seconds

## 🎨 UI/UX Consistency

### Bootstrap Classes
Use the same classes as Account Vulns:
- Cards: `card border-0 shadow-sm`
- Header: `card-header bg-light border-bottom`
- Body: `card-body`
- Table: `table table-hover`
- Badges: `badge bg-danger`, `badge bg-warning`, `badge bg-warning text-dark`

### Icons
- Page title: `bi-people-fill`
- Workgroup header: `bi-people-fill`
- Refresh: `bi-arrow-clockwise`
- Home: `bi-house`
- Arrow right: `bi-arrow-right`

### Colors
- Critical: `#dc3545` (red)
- High: `#fd7e14` (orange)
- Medium: `#ffc107` (yellow)
- Primary: `#0d6efd` (blue)

## 📝 Documentation

After implementation, update:
1. ✅ API docs: Document `/api/wg-vulns` endpoint
2. ✅ User guide: Add WG Vulns section
3. ✅ HISTORY.md: Add feature entry
4. ✅ Code comments: Document key methods and classes

## 🚢 Deployment

### Pre-deployment Checklist
- [ ] All tests passing (unit, integration, E2E)
- [ ] Code review completed
- [ ] Documentation updated
- [ ] Performance validated (<2s load time)
- [ ] Security reviewed (no workgroup leakage)

### Deployment Steps
1. Deploy backend (no migrations needed)
2. Deploy frontend
3. Clear CDN cache
4. Monitor logs for 24 hours
5. Collect user feedback

### Rollback Plan
If issues arise:
1. Hide sidebar menu item (quick fix)
2. Revert backend endpoint if needed
3. No database rollback required

## 🎓 Learning Resources

Reference implementations:
- **Backend**: `AccountVulnsService.kt`, `AccountVulnsController.kt`
- **Frontend**: `AccountVulnsView.tsx`, `accountVulnsService.ts`
- **Tests**: `AccountVulnsServiceTest.kt`, `AccountVulnsContractTest.kt`

Related features:
- **Feature 008**: Workgroup foundation
- **Feature 018**: Account Vulns (UI pattern)
- **Feature 019**: Severity breakdown (calculation logic)

## 💡 Quick Wins

Start with these to see progress fast:

**Day 1 Morning**: Create DTOs and repository methods
**Day 1 Afternoon**: Implement service (copy from Account Vulns)
**Day 2 Morning**: Implement controller with error handling
**Day 2 Afternoon**: Write backend tests
**Day 3 Morning**: Create frontend service and page
**Day 3 Afternoon**: Create React component (copy from Account Vulns)
**Day 4 Morning**: Update sidebar navigation
**Day 4 Afternoon**: Write E2E tests
**Day 5**: Polish, fix bugs, test thoroughly

## 🤝 Getting Help

If you're stuck:
1. Compare with Account Vulns implementation
2. Check [PLAN.md](./PLAN.md) for detailed specs
3. Review [wg-vulns-api.yaml](./contracts/wg-vulns-api.yaml) for API contract
4. Ask team for code review or pair programming

## ✅ Definition of Done

Feature is complete when:
- ✅ All backend tests passing (unit, integration, contract)
- ✅ All frontend tests passing (component, E2E)
- ✅ Code review approved
- ✅ Documentation updated
- ✅ Performance targets met (<2s load time)
- ✅ No security vulnerabilities
- ✅ Manual testing completed (all user flows)
- ✅ Deployed to production
- ✅ Monitoring in place (error logs, metrics)

Good luck! 🚀
