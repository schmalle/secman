# Quickstart Guide: Vulnerability Statistics Lense

**Feature**: 036-vuln-stats-lense
**Date**: 2025-10-28
**Audience**: Developers implementing this feature

## Overview

This guide provides a quick reference for implementing the Vulnerability Statistics Lense feature. It includes implementation order, key files, and common patterns from the codebase.

---

## Implementation Order

### Phase 1: Backend Foundation

1. **DTOs** (Data Transfer Objects)
   - Create 6 DTO classes in `src/backendng/src/main/kotlin/com/secman/dto/`
   - Reference: `data-model.md` for complete DTO definitions
   - Files to create:
     - `MostCommonVulnerabilityDto.kt`
     - `SeverityDistributionDto.kt`
     - `TopAssetByVulnerabilitiesDto.kt`
     - `VulnerabilityByAssetTypeDto.kt`
     - `TemporalTrendDataPointDto.kt`
     - `TemporalTrendsDto.kt`

2. **Service Layer** (Business Logic + Aggregation Queries)
   - Create `VulnerabilityStatisticsService.kt` in `src/backendng/src/main/kotlin/com/secman/service/`
   - Implement 5 methods (one per endpoint)
   - Apply workgroup filtering pattern from Feature 034 (OutdatedAssetService)
   - Reference: `VulnerabilityRepository` and `AssetRepository` for queries

3. **Repository Extensions** (Custom Queries)
   - Extend `VulnerabilityRepository.kt` with custom aggregation queries
   - Use `@Query` annotation with native SQL or JPQL
   - Example pattern from codebase: `OutdatedAssetMaterializedViewRepository`

4. **Controller Layer** (REST Endpoints)
   - Create `VulnerabilityStatisticsController.kt` in `src/backendng/src/main/kotlin/com/secman/controller/`
   - Implement 5 GET endpoints matching OpenAPI spec
   - Apply `@Secured(SecurityRule.IS_AUTHENTICATED)` annotation
   - Inject `Authentication` parameter for role checking

### Phase 2: Frontend Foundation

5. **API Client** (Axios Service)
   - Create `vulnerabilityStatisticsApi.ts` in `src/frontend/src/services/api/`
   - Implement 5 API methods with TypeScript interfaces
   - Use JWT from sessionStorage (existing pattern)

6. **Chart Components** (React Islands)
   - Create 5 React components in `src/frontend/src/components/statistics/`
   - Install Chart.js: `npm install chart.js react-chartjs-2 chartjs-plugin-zoom`
   - Components to create:
     - `MostCommonVulnerabilities.tsx` (table)
     - `SeverityDistributionChart.tsx` (pie chart)
     - `TopAssetsByVulnerabilities.tsx` (table/bar chart)
     - `VulnerabilityByAssetType.tsx` (grouped bar chart)
     - `TemporalTrendsChart.tsx` (line chart)

7. **Main Page** (Astro Page)
   - Create `vulnerability-statistics.astro` in `src/frontend/src/pages/`
   - Import all chart components as React islands (`client:load`)
   - Use Bootstrap 5.3 grid for responsive layout

8. **Navigation** (Sidebar Update)
   - Modify `Sidebar.tsx` in `src/frontend/src/components/layout/`
   - Add "Lense" as sub-item under "Vulnerability Management"
   - Apply role check (ADMIN or VULN)

### Phase 3: Testing (Per TDD Principle)

9. **Backend Tests**
   - Contract tests: `VulnerabilityStatisticsControllerTest.kt`
   - Unit tests: `VulnerabilityStatisticsServiceTest.kt`
   - Integration tests: `VulnerabilityStatisticsIntegrationTest.kt`

10. **Frontend Tests**
    - E2E tests: `vulnerability-statistics.spec.ts` (Playwright)
    - Test interactive features (drill-down navigation)

---

## Key Patterns & Examples

### Pattern 1: Workgroup Filtering (From Feature 034)

**Service Method Pattern**:
```kotlin
@Singleton
class VulnerabilityStatisticsService(
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val userService: UserService
) {
    fun getMostCommonVulnerabilities(authentication: Authentication): List<MostCommonVulnerabilityDto> {
        val username = authentication.name
        val isAdmin = authentication.roles.contains("ADMIN")

        return if (isAdmin) {
            // Admin sees all
            vulnerabilityRepository.findMostCommonVulnerabilitiesForAll()
        } else {
            // VULN users see only workgroup-assigned assets
            val workgroupIds = userService.getUserWorkgroupIds(username)
            vulnerabilityRepository.findMostCommonVulnerabilitiesForWorkgroups(workgroupIds)
        }
    }
}
```

**Location**: Reference `OutdatedAssetService.kt:42-67` for exact pattern

---

### Pattern 2: Custom Repository Query

**Repository Extension**:
```kotlin
@Repository
interface VulnerabilityRepository : CrudRepository<Vulnerability, Long> {

    @Query("""
        SELECT NEW com.secman.dto.MostCommonVulnerabilityDto(
            v.vulnerabilityId,
            COALESCE(v.cvssSeverity, 'UNKNOWN'),
            COUNT(v),
            COUNT(DISTINCT v.asset.id)
        )
        FROM Vulnerability v
        GROUP BY v.vulnerabilityId, v.cvssSeverity
        ORDER BY COUNT(v) DESC
    """)
    fun findMostCommonVulnerabilitiesForAll(pageable: Pageable = PageRequest.of(0, 10)): List<MostCommonVulnerabilityDto>

    @Query("""
        SELECT NEW com.secman.dto.MostCommonVulnerabilityDto(
            v.vulnerabilityId,
            COALESCE(v.cvssSeverity, 'UNKNOWN'),
            COUNT(v),
            COUNT(DISTINCT v.asset.id)
        )
        FROM Vulnerability v
        JOIN v.asset a
        JOIN a.workgroups w
        WHERE w.id IN :workgroupIds
        GROUP BY v.vulnerabilityId, v.cvssSeverity
        ORDER BY COUNT(v) DESC
    """)
    fun findMostCommonVulnerabilitiesForWorkgroups(
        workgroupIds: Set<Long>,
        pageable: Pageable = PageRequest.of(0, 10)
    ): List<MostCommonVulnerabilityDto>
}
```

**Location**: Create similar to `OutdatedAssetMaterializedViewRepository.kt`

---

### Pattern 3: REST Controller with Authentication

**Controller Example**:
```kotlin
@Controller("/api/vulnerability-statistics")
@Secured(SecurityRule.IS_AUTHENTICATED)
class VulnerabilityStatisticsController(
    private val vulnerabilityStatisticsService: VulnerabilityStatisticsService
) {

    @Get("/most-common")
    @Produces(MediaType.APPLICATION_JSON)
    fun getMostCommonVulnerabilities(authentication: Authentication): HttpResponse<List<MostCommonVulnerabilityDto>> {
        return try {
            val result = vulnerabilityStatisticsService.getMostCommonVulnerabilities(authentication)
            HttpResponse.ok(result)
        } catch (e: Exception) {
            HttpResponse.serverError()
        }
    }

    @Get("/severity-distribution")
    @Produces(MediaType.APPLICATION_JSON)
    fun getSeverityDistribution(authentication: Authentication): HttpResponse<SeverityDistributionDto> {
        val result = vulnerabilityStatisticsService.getSeverityDistribution(authentication)
        return HttpResponse.ok(result)
    }

    @Get("/temporal-trends")
    @Produces(MediaType.APPLICATION_JSON)
    fun getTemporalTrends(
        @QueryValue days: Int,
        authentication: Authentication
    ): HttpResponse<*> {
        if (days !in listOf(30, 60, 90)) {
            return HttpResponse.badRequest<String>().body("Invalid days parameter. Must be 30, 60, or 90.")
        }

        val result = vulnerabilityStatisticsService.getTemporalTrends(days, authentication)
        return HttpResponse.ok(result)
    }
}
```

**Location**: Reference `OutdatedAssetController.kt` for similar pattern

---

### Pattern 4: Chart.js React Component

**Pie Chart Example**:
```tsx
import React from 'react';
import { Pie } from 'react-chartjs-2';
import {
  Chart as ChartJS,
  ArcElement,
  Tooltip,
  Legend,
  type ChartEvent,
  type ActiveElement
} from 'chart.js';

ChartJS.register(ArcElement, Tooltip, Legend);

interface SeverityDistributionChartProps {
  data: {
    critical: number;
    high: number;
    medium: number;
    low: number;
  };
  onSeverityClick: (severity: string) => void;
}

export default function SeverityDistributionChart({ data, onSeverityClick }: SeverityDistributionChartProps) {
  const chartData = {
    labels: ['Critical', 'High', 'Medium', 'Low'],
    datasets: [{
      data: [data.critical, data.high, data.medium, data.low],
      backgroundColor: ['#dc3545', '#fd7e14', '#ffc107', '#0dcaf0'],
      borderColor: '#ffffff',
      borderWidth: 2
    }]
  };

  const options = {
    responsive: true,
    maintainAspectRatio: false,
    onClick: (event: ChartEvent, elements: ActiveElement[]) => {
      if (elements.length > 0) {
        const index = elements[0].index;
        const severity = chartData.labels[index].toLowerCase();
        onSeverityClick(severity);
      }
    }
  };

  return (
    <div className="card shadow-sm">
      <div className="card-header bg-primary text-white">
        <h5 className="mb-0">Severity Distribution</h5>
      </div>
      <div className="card-body">
        <div style={{ height: '350px' }}>
          <Pie data={chartData} options={options} />
        </div>
      </div>
    </div>
  );
}
```

**Location**: Full examples in `research.md` sections 4.1-4.4

---

### Pattern 5: Astro Page with React Islands

**Main Page Example**:
```astro
---
import Layout from '../layouts/Layout.astro';
import SeverityDistributionChart from '../components/statistics/SeverityDistributionChart.tsx';
import TemporalTrendsChart from '../components/statistics/TemporalTrendsChart.tsx';
---

<Layout title="Vulnerability Statistics - Lense">
  <div class="container-fluid py-4">
    <div class="row mb-4">
      <div class="col-12">
        <h1 class="display-5">Vulnerability Statistics Lense</h1>
        <p class="text-muted">Comprehensive vulnerability analytics and trends</p>
      </div>
    </div>

    <div class="row g-4">
      <!-- Severity Distribution -->
      <div class="col-12 col-lg-6">
        <SeverityDistributionChart
          client:load
          data={{ critical: 0, high: 0, medium: 0, low: 0 }}
          onSeverityClick={(severity) => {
            window.location.href = `/vulnerabilities?severity=${severity}`;
          }}
        />
      </div>

      <!-- Temporal Trends -->
      <div class="col-12 col-lg-6">
        <TemporalTrendsChart
          client:load
          data30={[]}
          data60={[]}
          data90={[]}
        />
      </div>
    </div>
  </div>
</Layout>
```

**Location**: Reference existing Astro pages in `src/frontend/src/pages/`

---

### Pattern 6: Sidebar Navigation Update

**Add Sub-Item to Sidebar**:
```tsx
// In Sidebar.tsx, under Vulnerability Management section:

{(userRoles.includes('ADMIN') || userRoles.includes('VULN')) && (
  <li className="nav-item">
    <a
      className="nav-link collapsed"
      data-bs-toggle="collapse"
      data-bs-target="#vulnCollapse"
      aria-expanded="false"
    >
      <i className="bi bi-shield-exclamation"></i>
      <span>Vulnerability Management</span>
    </a>
    <div id="vulnCollapse" className="collapse" data-bs-parent="#sidebarMenu">
      <ul className="nav flex-column ms-3">
        <li className="nav-item">
          <a
            className={`nav-link ${currentPath === '/vulnerabilities' ? 'active' : ''}`}
            href="/vulnerabilities"
          >
            Current Vulnerabilities
          </a>
        </li>
        <li className="nav-item">
          <a
            className={`nav-link ${currentPath === '/outdated-assets' ? 'active' : ''}`}
            href="/outdated-assets"
          >
            Outdated Assets
          </a>
        </li>
        <li className="nav-item">
          <a
            className={`nav-link ${currentPath === '/vulnerability-statistics' ? 'active' : ''}`}
            href="/vulnerability-statistics"
          >
            Lense
          </a>
        </li>
      </ul>
    </div>
  </li>
)}
```

**Location**: Modify `src/frontend/src/components/layout/Sidebar.tsx`

---

## File Locations Cheat Sheet

### Backend (Kotlin)

| Component | Directory | Files to Create/Modify |
|-----------|-----------|------------------------|
| DTOs | `src/backendng/src/main/kotlin/com/secman/dto/` | 6 new files |
| Service | `src/backendng/src/main/kotlin/com/secman/service/` | `VulnerabilityStatisticsService.kt` (NEW) |
| Repository | `src/backendng/src/main/kotlin/com/secman/repository/` | Extend `VulnerabilityRepository.kt`, `AssetRepository.kt` |
| Controller | `src/backendng/src/main/kotlin/com/secman/controller/` | `VulnerabilityStatisticsController.kt` (NEW) |
| Tests | `src/backendng/src/test/kotlin/com/secman/` | 3 new test files |

### Frontend (Astro + React)

| Component | Directory | Files to Create/Modify |
|-----------|-----------|------------------------|
| API Client | `src/frontend/src/services/api/` | `vulnerabilityStatisticsApi.ts` (NEW) |
| Components | `src/frontend/src/components/statistics/` | 5 new React components |
| Page | `src/frontend/src/pages/` | `vulnerability-statistics.astro` (NEW) |
| Layout | `src/frontend/src/components/layout/` | Modify `Sidebar.tsx` |
| Tests | `src/frontend/tests/e2e/` | `vulnerability-statistics.spec.ts` (NEW) |

---

## Common Commands

### Backend Development

```bash
# Navigate to backend
cd /Users/flake/sources/misc/secman/src/backendng

# Run tests
./gradlew test

# Run application
./gradlew run

# Build
./gradlew build
```

### Frontend Development

```bash
# Navigate to frontend
cd /Users/flake/sources/misc/secman/src/frontend

# Install Chart.js dependencies
npm install chart.js react-chartjs-2 chartjs-plugin-zoom

# Run dev server
npm run dev

# Run E2E tests
npm run test

# Build
npm run build
```

---

## Dependencies to Install

### Backend (No New Dependencies)
- All required dependencies already in `build.gradle.kts`
- Uses existing: Micronaut, Hibernate JPA, MariaDB connector

### Frontend (New Dependencies)
```json
{
  "dependencies": {
    "chart.js": "^4.4.1",
    "react-chartjs-2": "^5.3.0",
    "chartjs-plugin-zoom": "^2.2.0"
  }
}
```

---

## Testing Checklist

### Backend Tests (TDD - Write First)

- [ ] Contract test: `GET /api/vulnerability-statistics/most-common` returns 200 with array
- [ ] Contract test: `GET /api/vulnerability-statistics/severity-distribution` returns 200 with object
- [ ] Contract test: `GET /api/vulnerability-statistics/top-assets` returns 200 with array
- [ ] Contract test: `GET /api/vulnerability-statistics/by-asset-type` returns 200 with array
- [ ] Contract test: `GET /api/vulnerability-statistics/temporal-trends?days=30` returns 200
- [ ] Contract test: `GET /api/vulnerability-statistics/temporal-trends?days=999` returns 400
- [ ] Unit test: ADMIN user sees all statistics
- [ ] Unit test: VULN user sees only workgroup-filtered statistics
- [ ] Unit test: Empty database returns empty/zero statistics
- [ ] Integration test: Query performance <1s for 10,000 vulnerabilities

### Frontend Tests (E2E)

- [ ] Page loads successfully for ADMIN user
- [ ] Page loads successfully for VULN user
- [ ] Pie chart displays severity distribution
- [ ] Clicking pie chart segment navigates to filtered vulnerabilities
- [ ] Line chart displays temporal trends
- [ ] Time range selector (30/60/90 days) updates chart
- [ ] Top assets table displays correctly
- [ ] Clicking asset row navigates to asset detail page

---

## Performance Targets

- **Backend API Response**: <1s per endpoint for 10,000 vulnerabilities
- **Frontend Page Load**: <3s total page load (FR-015)
- **Chart Rendering**: <100ms per chart with decimation enabled

---

## Reference Documents

- **Feature Spec**: `specs/036-vuln-stats-lense/spec.md`
- **Research**: `specs/036-vuln-stats-lense/research.md`
- **Data Model**: `specs/036-vuln-stats-lense/data-model.md`
- **API Contracts**: `specs/036-vuln-stats-lense/contracts/vulnerability-statistics-api.yaml`
- **Implementation Plan**: `specs/036-vuln-stats-lense/plan.md`

---

## Common Pitfalls & Solutions

### Pitfall 1: Workgroup Filtering Forgotten
**Problem**: Statistics show all data regardless of user role
**Solution**: Always check `authentication.roles` and apply workgroup filter for non-ADMIN users

### Pitfall 2: Chart.js Components Not Registered
**Problem**: "Element type 'arc' is not registered" error
**Solution**: Always import and register Chart.js components at top of React component:
```tsx
import { Chart as ChartJS, ArcElement, Tooltip, Legend } from 'chart.js';
ChartJS.register(ArcElement, Tooltip, Legend);
```

### Pitfall 3: Large Dataset Performance
**Problem**: Charts slow or unresponsive with 10,000+ vulnerabilities
**Solution**: Enable decimation plugin in chart options:
```tsx
plugins: {
  decimation: { enabled: true, algorithm: 'lttb', samples: 500 }
}
```

### Pitfall 4: React Island Not Hydrating
**Problem**: Chart component not interactive in Astro page
**Solution**: Use `client:load` directive on component:
```astro
<SeverityDistributionChart client:load data={...} />
```

### Pitfall 5: Null Severity Handling
**Problem**: Queries fail or return incorrect counts with null severity values
**Solution**: Use `COALESCE(v.cvssSeverity, 'UNKNOWN')` in all GROUP BY queries

---

## Next Steps

After completing implementation:

1. **Run full test suite**: Ensure ≥80% coverage (TDD requirement)
2. **Performance test**: Load 10,000+ vulnerabilities and verify <3s page load
3. **Security review**: Verify workgroup filtering works correctly
4. **Update CLAUDE.md**: Add new endpoints and patterns to project documentation
5. **Create PR**: Follow Git workflow (`feat(statistics): add vulnerability statistics lense`)

---

## Questions?

Refer to:
- Constitution: `.specify/memory/constitution.md`
- Project README: `CLAUDE.md`
- Similar Feature (034): `specs/034-outdated-assets/`
