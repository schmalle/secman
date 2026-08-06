# Research: VULN Role & Vulnerability Management UI

**Feature**: 004-i-want-to
**Date**: 2025-10-03
**Status**: Complete

## Research Objectives

Investigate existing patterns in secman codebase to inform implementation of:
1. New VULN role and role-based access control
2. Query for "current" (latest scan per asset) vulnerabilities
3. Exception matching logic (IP-based and product-based)
4. Sidebar navigation with collapsible submenu
5. Frontend filtering/sorting UI for vulnerability lists

---

## 1. Role-Based Access Control (RBAC) Patterns

### Decision: Use `@Secured` Annotations with Role-OR Logic

**Backend Implementation**:
```kotlin
@Controller("/api/vulnerability-management")
@Secured(SecurityRule.IS_AUTHENTICATED)
class VulnerabilityManagementController {

    @Get("/current")
    @Secured("ADMIN", "VULN")  // Admin OR Vuln role required
    fun getCurrentVulnerabilities(...): List<VulnerabilityDto> { ... }
}
```

**Frontend Implementation**:
```typescript
// src/frontend/src/utils/auth.ts
export function hasVulnAccess(): boolean {
    return hasRole('ADMIN') || hasRole('VULN');
}

// Sidebar.tsx conditional rendering
{hasVulnAccess() && (
    <li>
        <a href="/vulnerabilities/current">
            <i className="bi bi-shield-exclamation me-2"></i> Vuln Management
        </a>
    </li>
)}
```

**Rationale**:
- Micronaut Security's `@Secured` annotation accepts multiple roles with implicit OR logic
- Consistent with existing patterns: `ScanController.kt:55` uses `@Secured("ADMIN")`
- No inline role checks (`authentication.roles.contains()`) found in codebase
- Frontend `hasRole()` utility exists in `auth.ts:31` for UI conditional rendering

**Code References**:
- Backend: `src/backendng/src/main/kotlin/com/secman/controller/ScanController.kt:55,118,182`
- Backend: `src/backendng/src/main/kotlin/com/secman/controller/UserController.kt:15`
- Frontend: `src/frontend/src/utils/auth.ts:31-34`
- Frontend: `src/frontend/src/components/Sidebar.tsx:133-139`

**Alternatives Considered**:
- **Inline role checks in controller methods**: Not used anywhere in codebase; rejected for consistency
- **Custom authorization filter**: Overkill for simple role check; `@Secured` is sufficient

---

## 2. Current Vulnerability Query Pattern

### Decision: Application-Layer Filtering with `groupBy`

**Repository Method** (Micronaut Data derived query):
```kotlin
interface VulnerabilityRepository : CrudRepository<Vulnerability, Long> {
    fun findAllByOrderByScanTimestampDesc(): List<Vulnerability>
    fun findByAssetIdOrderByScanTimestampDesc(assetId: Long): List<Vulnerability>
}
```

**Service Logic** (Kotlin groupBy):
```kotlin
@Singleton
class VulnerabilityService(
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val assetRepository: AssetRepository
) {
    fun getCurrentVulnerabilities(): List<VulnerabilityWithAssetDto> {
        val allVulns = vulnerabilityRepository.findAllByOrderByScanTimestampDesc()

        // Get latest scan timestamp per asset
        val latestScanPerAsset = allVulns.groupBy { it.asset.id }
            .mapValues { (_, vulns) -> vulns.maxOf { it.scanTimestamp } }

        // Filter vulnerabilities to only those from latest scan
        return allVulns.filter { vuln ->
            vuln.scanTimestamp == latestScanPerAsset[vuln.asset.id]
        }.map { /* map to DTO */ }
    }
}
```

**Rationale**:
- **No window functions** (ROW_NUMBER, PARTITION BY) found in existing codebase
- Existing pattern uses derived query methods: `VulnerabilityRepository.kt:26` (`findByAssetIdAndScanTimestampBetween`)
- Kotlin's `groupBy` and collection operations are idiomatic and performant for typical dataset sizes
- Simpler to test than complex JPQL queries
- Consistent with existing service layer patterns: `VulnerabilityImportService.kt:89-94`

**Performance Considerations**:
- For <1000 assets with <100 vulnerabilities each: in-memory filtering is efficient
- If dataset grows beyond 10,000 total vulnerabilities, consider JPQL subquery optimization

**Code References**:
- `src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt:26`
- `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityImportService.kt:89-94`

**Alternatives Considered**:
- **JPQL with subquery**: More complex, harder to test, premature optimization
- **Native SQL with window functions**: No existing usage; breaks from established patterns
- **Pre-computed "current" flag on Vulnerability**: Adds complexity to import logic; rejected

---

## 3. Exception Matching Logic

### Decision: Query Active Exceptions, Match in Service Layer

**VulnerabilityException Entity**:
```kotlin
enum class ExceptionType { IP, PRODUCT }

@Entity
data class VulnerabilityException(
    var exceptionType: ExceptionType,
    var targetValue: String,  // IP address OR product name pattern
    var expirationDate: LocalDateTime? = null,  // null = permanent
    var reason: String
)
```

**Matching Logic**:
```kotlin
@Singleton
class VulnerabilityExceptionService(
    private val exceptionRepository: VulnerabilityExceptionRepository
) {
    fun getActiveExceptions(): List<VulnerabilityException> {
        val now = LocalDateTime.now()
        return exceptionRepository.findAll().filter { exc ->
            exc.expirationDate == null || exc.expirationDate!! > now
        }
    }

    fun isVulnerabilityExcepted(vuln: Vulnerability, asset: Asset): Boolean {
        val activeExceptions = getActiveExceptions()

        return activeExceptions.any { exc ->
            when (exc.exceptionType) {
                ExceptionType.IP -> asset.ip == exc.targetValue
                ExceptionType.PRODUCT -> vuln.vulnerableProductVersions?.contains(exc.targetValue) == true
            }
        }
    }
}
```

**Rationale**:
- Separate concerns: Repository fetches all exceptions, service applies expiration + matching logic
- Kotlin's `when` expression is idiomatic for type-based branching
- Caching opportunity: `getActiveExceptions()` can be cached with 5-minute TTL if needed
- Simple contains check for product matching (not regex) avoids security concerns

**Code References**:
- Enum pattern: `src/backendng/src/main/kotlin/com/secman/domain/AssessmentBasisType.kt:3-5`
- Service composition: `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityImportService.kt:27-30`

**Alternatives Considered**:
- **Regex pattern matching for products**: More flexible but slower and potential ReDoS vulnerability
- **Join query in repository**: Complex JPQL; service layer is clearer separation of concerns
- **Pre-computed exception status on Vulnerability**: Requires recalculation on exception changes

---

## 4. Sidebar Navigation with Submenu

### Decision: Bootstrap Collapse Component with Nested List

**Implementation Pattern**:
```tsx
// Sidebar.tsx
const [vulnMenuOpen, setVulnMenuOpen] = useState(false);

{hasVulnAccess() && (
    <>
        <li>
            <button
                className="btn btn-link d-flex align-items-center w-100 text-start"
                onClick={() => setVulnMenuOpen(!vulnMenuOpen)}
            >
                <i className="bi bi-shield-exclamation me-2"></i>
                Vuln Management
                <i className={`bi bi-chevron-${vulnMenuOpen ? 'down' : 'right'} ms-auto`}></i>
            </button>
        </li>
        {vulnMenuOpen && (
            <ul className="list-unstyled ps-4">
                <li>
                    <a href="/vulnerabilities/current" className="d-flex align-items-center">
                        <i className="bi bi-list-ul me-2"></i> Vulns
                    </a>
                </li>
                <li>
                    <a href="/vulnerabilities/exceptions" className="d-flex align-items-center">
                        <i className="bi bi-x-circle me-2"></i> Exceptions
                    </a>
                </li>
            </ul>
        )}
    </>
)}
```

**Rationale**:
- Sidebar.tsx is already React component (not Astro): `src/frontend/src/components/Sidebar.tsx`
- Uses `useState` for expandable sections (no Bootstrap collapse data attributes needed)
- Bootstrap icons (`bi-*`) used throughout: `Sidebar.tsx:107,119,133,141`
- Indented submenu with `ps-4` (padding-start) class follows Bootstrap conventions

**Code References**:
- `src/frontend/src/components/Sidebar.tsx:19-20` (useState for state management)
- `src/frontend/src/components/Sidebar.tsx:107,119,133` (icon usage, link structure)

**Alternatives Considered**:
- **Bootstrap Collapse component with data-bs-toggle**: More complex than simple useState
- **Separate Sidebar.astro component**: Sidebar.tsx already exists; no need to refactor

---

## 5. Frontend Filtering/Sorting UI

### Decision: Client-Side Filtering with Form Selects

**Implementation Pattern**:
```tsx
const [vulnerabilities, setVulnerabilities] = useState<VulnerabilityDto[]>([]);
const [severityFilter, setSeverityFilter] = useState<string>('all');
const [systemFilter, setSystemFilter] = useState<string>('all');
const [exceptionFilter, setExceptionFilter] = useState<string>('all');

const filteredVulns = vulnerabilities.filter(v =>
    (severityFilter === 'all' || v.severity === severityFilter) &&
    (systemFilter === 'all' || v.assetName === systemFilter) &&
    (exceptionFilter === 'all' ||
        (exceptionFilter === 'excepted' && v.hasException) ||
        (exceptionFilter === 'not-excepted' && !v.hasException))
);

// Render filters
<div className="row mb-3">
    <div className="col-md-4">
        <label>Severity</label>
        <select className="form-select" value={severityFilter}
                onChange={e => setSeverityFilter(e.target.value)}>
            <option value="all">All Severities</option>
            <option value="Critical">Critical</option>
            <option value="High">High</option>
            <option value="Medium">Medium</option>
            <option value="Low">Low</option>
        </select>
    </div>
    {/* System and Exception filters similar */}
</div>
```

**Rationale**:
- No existing DataTables library usage in codebase (checked `package.json`)
- All tables use Bootstrap styling: `AssetManagement.tsx:281` (`table table-striped table-hover`)
- Client-side filtering avoids additional API calls for simple use cases
- Dropdown filters more compact than search boxes for categorical data

**Code References**:
- Table pattern: `src/frontend/src/components/AssetManagement.tsx:281-319`
- Bootstrap form-select: Bootstrap 5.3 documentation (no existing usage found, but standard pattern)

**Sorting Pattern**:
```tsx
const [sortField, setSortField] = useState<string>('scanTimestamp');
const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc');

const sortedVulns = [...filteredVulns].sort((a, b) => {
    const aVal = a[sortField];
    const bVal = b[sortField];
    const cmp = aVal > bVal ? 1 : aVal < bVal ? -1 : 0;
    return sortOrder === 'asc' ? cmp : -cmp;
});

// Clickable table headers
<th onClick={() => handleSort('severity')}>
    Severity {sortField === 'severity' && (sortOrder === 'asc' ? '↑' : '↓')}
</th>
```

**Code References**:
- No existing sorting implementation found; using standard React pattern

**Alternatives Considered**:
- **Server-side filtering with query params**: More complex, requires backend API changes
- **Third-party table library (e.g., react-table)**: Inconsistent with existing custom patterns
- **Full-text search**: Overkill for categorical filters

---

## Summary of Decisions

| Component | Decision | Rationale |
|-----------|----------|-----------|
| **RBAC** | `@Secured("ADMIN", "VULN")` annotations | Consistent with existing patterns |
| **Current Vuln Query** | Application-layer groupBy filtering | No window functions in codebase, simpler to test |
| **Exception Matching** | Service-layer logic with enum-based dispatch | Clean separation of concerns, cacheable |
| **Sidebar Submenu** | React useState with nested `<ul>` | Sidebar.tsx already React component |
| **Filtering UI** | Client-side filtering with form-select dropdowns | No DataTables, consistent with Bootstrap patterns |
| **Sorting UI** | Clickable table headers with state management | Standard React pattern, no library needed |

---

## Dependencies Confirmed

- **Backend**: Micronaut 4.4, Hibernate JPA, Kotlin 2.1.0 (all existing)
- **Frontend**: React 19, Bootstrap 5.3, Axios (all existing)
- **Database**: MariaDB 11.4 (existing)
- **No new dependencies required**

---

## Performance Baseline

- **Expected vulnerability count**: <1000 per asset, <10,000 total
- **Expected exception count**: <100 active exceptions
- **Target response time**: <200ms p95 for `/api/vulnerabilities/current`
- **Optimization strategy**: If dataset exceeds 10,000 vulnerabilities, migrate to JPQL subquery with database-side filtering

---

**Research Status**: ✅ Complete
**Next Phase**: Phase 1 - Design & Contracts
