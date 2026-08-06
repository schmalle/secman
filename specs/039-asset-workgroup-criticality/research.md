# Asset and Workgroup Criticality Classification - Technical Implementation Research

**Feature**: 039-asset-workgroup-criticality
**Research Date**: 2025-11-01
**Stack**: Kotlin 2.2.21, Micronaut 4.10, Hibernate JPA, MariaDB 11.4, Astro 5.14, React 19, Bootstrap 5.3

---

## 1. Enum Design Pattern (Kotlin/JPA)

**Decision**: Standalone enum file in `/domain` package, shared across entities

**Rationale**:
- Existing codebase uses **both patterns** (nested and standalone), but standalone enums are preferred for cross-entity sharing
- Analysis of codebase shows:
  - **Standalone enums** used for: `NotificationType`, `ExceptionRequestStatus`, `ExceptionScope`, `RefreshJobStatus`, `AuditEventType`, `AuditSeverity`, `EmailProvider`, `EmailStatus`, `TestAccountStatus`, `IpRangeType`, `McpOperation`, `McpEventType`, `McpPermission`
  - **Nested enums** used for: `User.Role`, `VulnerabilityException.ExceptionType`, `Release.ReleaseStatus`, `IdentityProvider.ProviderType`, `Demand.DemandType/Priority/DemandStatus`, `Response.AnswerType`
- **Criticality will be shared** between Workgroup and Asset entities → standalone is correct choice
- Precedent: `NotificationType` (Feature 035) is standalone and shared across multiple features

**Implementation Notes**:

```kotlin
// File: /src/backendng/src/main/kotlin/com/secman/domain/Criticality.kt
package com.secman.domain

/**
 * Criticality level for workgroups and assets
 * Feature: 039-asset-workgroup-criticality
 */
enum class Criticality {
    CRITICAL,  // Highest priority - business-critical systems
    HIGH,      // Important systems requiring prompt attention
    MEDIUM,    // Standard business systems
    LOW;       // Non-critical development/test systems

    /**
     * Get user-friendly display label
     */
    fun displayName(): String = when (this) {
        CRITICAL -> "Critical"
        HIGH -> "High"
        MEDIUM -> "Medium"
        LOW -> "Low"
    }

    /**
     * Get Bootstrap color class for UI rendering
     */
    fun bootstrapColor(): String = when (this) {
        CRITICAL -> "danger"
        HIGH -> "warning"
        MEDIUM -> "info"
        LOW -> "secondary"
    }
}
```

**Usage in Entities**:

```kotlin
// Workgroup entity
@Enumerated(EnumType.STRING)
@Column(name = "criticality", length = 20, nullable = true)
var criticality: Criticality? = null

// Asset entity
@Enumerated(EnumType.STRING)
@Column(name = "criticality", length = 20, nullable = true)
var criticality: Criticality? = null

@Column(name = "inherit_criticality", nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
var inheritCriticality: Boolean = true
```

**Alternatives Considered**:
- **Nested in Workgroup**: Would require `Workgroup.Criticality` imports in Asset, less discoverable
- **String constants**: No type safety, error-prone, harder to maintain
- **Separate enums**: Code duplication, maintenance burden, potential inconsistency

**References**:
- Existing pattern: `NotificationType.kt` (standalone, shared across entities)
- Existing pattern: `ExceptionRequestStatus.kt` (standalone with helper methods)
- JPA Best Practice: `@Enumerated(EnumType.STRING)` for database portability and readability

---

## 2. Inheritance Logic Implementation

**Decision**: Transient `@Transient` property with Kotlin getter, calculated on-demand in entity

**Rationale**:
- **Performance**: No database storage needed, avoids data duplication and sync issues
- **Simplicity**: Logic lives close to data, easy to test and maintain
- **Consistency**: Calculated on every access ensures always accurate
- **Existing Pattern**: Asset entity already has computed fields (`ipNumeric` in `@PrePersist/@PreUpdate`)
- **N+1 Query Avoidance**: Workgroup relationship is already `EAGER` fetched in Asset (`@ManyToMany(fetch = FetchType.EAGER)` at line 110)

**Implementation Notes**:

```kotlin
// Asset.kt - Add to entity
@Transient
fun getEffectiveCriticality(): Criticality? {
    // Priority 1: Explicit asset criticality (inheritance disabled)
    if (!inheritCriticality && criticality != null) {
        return criticality
    }

    // Priority 2: Highest criticality from workgroups (when inheriting)
    if (inheritCriticality && workgroups.isNotEmpty()) {
        // Filter non-null criticalities and return highest priority
        val workgroupCriticalities = workgroups.mapNotNull { it.criticality }
        if (workgroupCriticalities.isNotEmpty()) {
            return workgroupCriticalities.minByOrNull { it.ordinal } // CRITICAL=0, LOW=3
        }
    }

    // Priority 3: Explicit asset criticality (fallback even if inheriting)
    if (criticality != null) {
        return criticality
    }

    // Priority 4: No criticality defined anywhere
    return null
}
```

**Service Layer Usage**:

```kotlin
// AssetService.kt - Example filtering by effective criticality
fun getAssetsByEffectiveCriticality(criticality: Criticality): List<Asset> {
    return assetRepository.findAll()
        .filter { it.getEffectiveCriticality() == criticality }
}
```

**Performance Characteristics**:
- **Read**: O(1) if no inheritance, O(n) where n = workgroup count (typically 1-3)
- **Write**: No overhead, only updates own criticality field
- **Memory**: No additional storage vs. duplicated field approach
- **EAGER Fetch**: Workgroups already loaded, no extra queries

**Alternatives Considered**:
- **Database computed column**: MariaDB supports generated columns, but cannot reference join table data (workgroups)
- **Service layer method**: Separates logic from entity, harder to serialize in DTOs, requires explicit calls
- **Cached field updated on save**: Complex synchronization, requires listeners, prone to stale data
- **@Formula annotation**: Hibernate feature, but limited to single table expressions, cannot JOIN

**References**:
- Existing pattern: `VulnerabilityException.isActive()` (line 118) - transient business logic method
- Existing pattern: `ExceptionRequestStatus.canTransitionTo()` (line 74) - enum helper methods
- JPA Best Practice: Use `@Transient` for derived/computed fields not stored in DB

---

## 3. Bulk Propagation Strategy

**Decision**: **No automatic propagation required** - criticality is calculated on-demand via `getEffectiveCriticality()`

**Rationale**:
- **Transient calculation** eliminates need for bulk updates
- When workgroup criticality changes, assets automatically reflect new value on next read
- **Zero database writes** required for propagation
- **ACID compliance**: No transaction boundaries to manage, no race conditions
- Existing codebase precedent: Materialized views use async batch processing, but that's for pre-computed aggregations; criticality is simpler

**Implementation Notes**:

```kotlin
// WorkgroupService.kt - Update method (no propagation needed)
@Transactional
open fun updateWorkgroupCriticality(id: Long, criticality: Criticality?): Workgroup {
    val workgroup = workgroupRepository.findById(id).orElseThrow {
        IllegalArgumentException("Workgroup not found: $id")
    }

    workgroup.criticality = criticality
    return workgroupRepository.update(workgroup)

    // No asset update needed - inheritance is automatic via getEffectiveCriticality()
}
```

**User Feedback Strategy** (if explicit propagation were needed):

For reference, if we needed to show propagation impact:

```kotlin
// Impact preview endpoint (read-only, no writes)
@Get("/workgroups/{id}/criticality-impact")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun getCriticalityImpact(id: Long, newCriticality: Criticality?): CriticalityImpact {
    val workgroup = workgroupService.getWorkgroupById(id)
    val affectedAssets = assetRepository.findByWorkgroupsIdOrderByNameAsc(id)
        .filter { it.inheritCriticality }

    return CriticalityImpact(
        workgroupId = id,
        workgroupName = workgroup.name,
        currentCriticality = workgroup.criticality,
        newCriticality = newCriticality,
        affectedAssetCount = affectedAssets.size,
        affectedAssetNames = affectedAssets.map { it.name }
    )
}
```

**Alternatives Considered**:
- **Synchronous bulk update**:
  - Pros: Explicit, clear audit trail
  - Cons: Slow for 1000+ assets, locks tables, requires transaction management, duplicates data
  - Rejected: Unnecessary with transient calculation approach

- **Async batch update with progress indicator** (like MaterializedViewRefreshService):
  - Pros: Non-blocking, shows progress, handles large datasets
  - Cons: Complex (job tracking, SSE events, error handling), still duplicates data
  - Pattern exists in codebase: `MaterializedViewRefreshService.kt` (lines 88-106, `@Async`, batch size 1000)
  - Rejected: Overkill for simple inheritance logic

- **Database triggers**:
  - Pros: Database-enforced consistency
  - Cons: Outside JPA/Hibernate control, harder to test, violates "no database logic" principle
  - Rejected: Project uses Hibernate DDL auto-migration, triggers not in DDL

**Performance Benchmarks** (estimated):
- **Transient calculation**: <1ms per asset (in-memory enum comparison)
- **Hypothetical sync update**: 500ms for 1000 assets (50 assets/batch, 20 batches × 25ms each)
- **Hypothetical async update**: 5-10s total time for 1000 assets (non-blocking)

**References**:
- Existing pattern: `MaterializedViewRefreshService.kt` (Feature 034) - async batch processing example
- Existing pattern: `WorkgroupService.assignAssetsToWorkgroup()` (line 158) - synchronous batch update
- Micronaut `@Async` docs: Requires `@Singleton open` for AOP proxy

---

## 4. React Badge Component Design

**Decision**: Create reusable `CriticalityBadge.tsx` component with color + icon + text, following Bootstrap 5.3 patterns

**Rationale**:
- **Accessibility**: WCAG 2.1 AA compliance requires color not be sole indicator
- **Consistency**: Matches existing `SeverityBadge.tsx` and `ExceptionStatusBadge.tsx` components
- **Reusability**: Single component for workgroup tables, asset tables, detail views
- **Bootstrap 5.3**: Uses opacity utilities (`bg-opacity-10`) and border classes for subtle professional look
- **Icon Library**: Bootstrap Icons already in use (loaded in project, seen in `SeverityBadge.tsx` line 84)

**Implementation Notes**:

```tsx
// File: /src/frontend/src/components/CriticalityBadge.tsx
import React from 'react';

export type CriticalityLevel = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

interface CriticalityBadgeProps {
  criticality: CriticalityLevel | null | undefined;
  className?: string;
  showLabel?: boolean; // Default true, set false for compact display
}

/**
 * Configuration for each criticality level
 * Pattern follows SeverityBadge.tsx (Feature 019)
 */
const criticalityConfig = {
  CRITICAL: {
    bgClass: 'bg-danger bg-opacity-10 border border-danger',
    textClass: 'text-danger',
    icon: 'bi-exclamation-triangle-fill',
    label: 'Critical',
    ariaLabel: 'Critical priority',
  },
  HIGH: {
    bgClass: 'bg-warning bg-opacity-10 border border-warning',
    textClass: 'text-warning',
    icon: 'bi-arrow-up-circle-fill',
    label: 'High',
    ariaLabel: 'High priority',
  },
  MEDIUM: {
    bgClass: 'bg-info bg-opacity-10 border border-info',
    textClass: 'text-info',
    icon: 'bi-dash-circle-fill',
    label: 'Medium',
    ariaLabel: 'Medium priority',
  },
  LOW: {
    bgClass: 'bg-secondary bg-opacity-10 border border-secondary',
    textClass: 'text-secondary',
    icon: 'bi-circle',
    label: 'Low',
    ariaLabel: 'Low priority',
  },
};

/**
 * Criticality badge component for workgroups and assets
 *
 * Features:
 * - Color-coded badges with subtle background (WCAG AA compliant)
 * - Bootstrap Icons for visual distinction (not just color)
 * - Accessible labels for screen readers
 * - Handles null/undefined (shows "Not Set" badge)
 * - Inheritance indicator (optional icon for inherited criticality)
 *
 * Related to: Feature 039 - Asset and Workgroup Criticality Classification
 *
 * @example
 * ```tsx
 * <CriticalityBadge criticality="CRITICAL" />
 * <CriticalityBadge criticality={asset.getEffectiveCriticality()} />
 * <CriticalityBadge criticality={null} /> {/* Shows "Not Set" */}
 * ```
 */
const CriticalityBadge: React.FC<CriticalityBadgeProps> = ({
  criticality,
  className = '',
  showLabel = true
}) => {
  // Handle null/undefined criticality
  if (!criticality) {
    return (
      <span
        className={`badge bg-light text-muted border border-secondary ${className}`.trim()}
        title="No criticality level assigned"
        role="status"
        aria-label="No criticality assigned"
      >
        <i className="bi bi-dash-circle me-1" aria-hidden="true"></i>
        <span>Not Set</span>
      </span>
    );
  }

  const config = criticalityConfig[criticality];

  return (
    <span
      className={`badge ${config.bgClass} ${config.textClass} ${className}`.trim()}
      title={`${config.label} criticality level`}
      role="status"
      aria-label={config.ariaLabel}
    >
      <i className={`bi ${config.icon} me-1`} aria-hidden="true"></i>
      <span className="visually-hidden">{config.ariaLabel}: </span>
      {showLabel && <span>{config.label}</span>}
    </span>
  );
};

export default React.memo(CriticalityBadge);
```

**Usage Examples**:

```tsx
// Workgroup table
<td>
  <CriticalityBadge criticality={workgroup.criticality} />
</td>

// Asset table with inheritance indicator
<td>
  <CriticalityBadge criticality={asset.effectiveCriticality} />
  {asset.inheritCriticality && asset.effectiveCriticality !== asset.criticality && (
    <i className="bi bi-arrow-down-up ms-1 text-muted"
       title="Inherited from workgroup"
       aria-label="Inherited criticality"></i>
  )}
</td>

// Compact display (icon only)
<CriticalityBadge criticality="CRITICAL" showLabel={false} />
```

**Accessibility Checklist**:
- ✅ Color + Icon + Text (triple redundancy for WCAG 2.1 Level AA)
- ✅ `aria-label` for screen readers
- ✅ `role="status"` for dynamic content
- ✅ `title` attribute for hover tooltips
- ✅ `visually-hidden` text for context
- ✅ Sufficient color contrast (Bootstrap contextual colors meet AA standards)
- ✅ Icon has `aria-hidden="true"` (text provides semantic meaning)

**Alternatives Considered**:
- **Text-only badges**: Fails accessibility (color-blind users), less visual impact
- **Color-only badges**: WCAG violation, rejected
- **Font Awesome icons**: Project uses Bootstrap Icons consistently, no need for second library
- **Custom SVG icons**: Overkill, Bootstrap Icons sufficient

**References**:
- Existing pattern: `SeverityBadge.tsx` (Feature 019, lines 1-92) - identical structure
- Existing pattern: `ExceptionStatusBadge.tsx` (Feature 031, lines 1-117) - status badge example
- Bootstrap 5.3 Badges: https://getbootstrap.com/docs/5.3/components/badge/
- Bootstrap Icons: https://icons.getbootstrap.com/ (exclamation-triangle-fill, arrow-up-circle-fill, etc.)
- WCAG 2.1 Use of Color: https://www.w3.org/WAI/WCAG21/Understanding/use-of-color.html

---

## 5. Filter/Sort Performance

**Decision**: Single-column index on `criticality` for both tables, composite indexes for common query patterns

**Rationale**:
- **MariaDB VARCHAR(20) indexing**: Efficient for enum strings, minimal overhead (~20 bytes per row)
- **Cardinality**: 4 distinct values (CRITICAL, HIGH, MEDIUM, LOW) + NULL → good selectivity
- **Query patterns**: Filtering by criticality is common, sorting by criticality + name is expected
- **Existing precedent**: Project uses extensive indexing (49 indexes across domain entities)
- **Index strategy**: Single-column for simple filters, composite for combined sorts

**Implementation Notes**:

```kotlin
// Workgroup.kt - Add to @Table annotation
@Table(
    name = "workgroup",
    indexes = [
        Index(name = "idx_workgroup_criticality", columnList = "criticality")
    ]
)

// Asset.kt - Add to existing indexes (line 11-16)
@Table(
    name = "asset",
    indexes = [
        Index(name = "idx_asset_ip_numeric", columnList = "ip_numeric"),
        Index(name = "idx_asset_criticality", columnList = "criticality"),
        Index(name = "idx_asset_criticality_name", columnList = "criticality,name") // Composite for sorted lists
    ]
)
```

**Query Optimization Examples**:

```kotlin
// AssetRepository.kt - Add method for criticality filtering
@Query("""
    SELECT a FROM Asset a
    WHERE a.criticality = :criticality
    ORDER BY a.name ASC
""")
fun findByCriticality(criticality: Criticality, pageable: Pageable): Page<Asset>

// Uses idx_asset_criticality_name composite index
// EXPLAIN: Using index idx_asset_criticality_name, Using filesort NOT needed

// Filter by effective criticality (inheritance logic - requires service layer)
// Cannot use index due to transient calculation - acceptable tradeoff
fun findByEffectiveCriticality(criticality: Criticality): List<Asset> {
    return findAll().filter { it.getEffectiveCriticality() == criticality }
}
```

**Performance Benchmarks** (estimated):

| Operation | Without Index | With Index | Notes |
|-----------|--------------|------------|-------|
| Filter by criticality (1000 assets) | 50ms (full scan) | 5ms (index seek) | 10x improvement |
| Sort by criticality + name | 80ms (filesort) | 15ms (index scan) | 5x improvement |
| Filter inherited criticality | 100ms (full scan + calculation) | 100ms (unavoidable) | Transient field, no index possible |

**Index Size Overhead**:
- **Single column**: ~20KB per 1000 rows (VARCHAR(20) + pointer)
- **Composite**: ~40KB per 1000 rows (criticality + name prefix)
- **Total overhead**: ~60KB per 1000 rows (negligible for typical datasets)

**Migration SQL** (Hibernate will auto-generate, for reference):

```sql
-- Workgroup table
CREATE INDEX idx_workgroup_criticality ON workgroup(criticality);

-- Asset table
CREATE INDEX idx_asset_criticality ON asset(criticality);
CREATE INDEX idx_asset_criticality_name ON asset(criticality, name);

-- Query plan verification
EXPLAIN SELECT * FROM asset WHERE criticality = 'CRITICAL' ORDER BY name;
-- Expected: type=ref, key=idx_asset_criticality_name, Extra=Using index condition
```

**Alternatives Considered**:
- **No index**: Simple queries only, but slow for 1000+ assets (50-100ms), rejected
- **Full-text index**: Overkill for enum values, not supported on VARCHAR(20)
- **Covering index with all columns**: Massive storage overhead (KB per row), index maintenance cost too high
- **Separate index on `inherit_criticality`**: Low cardinality (boolean), minimal benefit, rejected

**Composite Index Strategy**:
- **When to use**: Queries with `WHERE criticality = ? ORDER BY name` pattern
- **When not to use**: Queries filtering by name only (won't use composite, falls back to table scan)
- **Existing pattern**: Project has 15 composite indexes (e.g., `idx_vuln_req_status` at VulnerabilityExceptionRequest.kt:51)

**References**:
- Existing pattern: `Asset.kt` line 14 - single-column index `idx_asset_ip_numeric`
- Existing pattern: `VulnerabilityExceptionRequest.kt` lines 50-55 - multiple single + composite indexes
- Existing pattern: `OutdatedAssetMaterializedView.kt` line 22 - composite index for severity filtering
- MariaDB Index Guidelines: https://mariadb.com/kb/en/getting-started-with-indexes/
- Index Cardinality: 4-5 distinct values is acceptable for B-tree indexes

---

## Cross-Cutting Concerns

### Testing Strategy

**Unit Tests** (following existing TDD pattern):
```kotlin
// CriticalityTest.kt
class CriticalityTest {
    @Test
    fun `displayName returns correct labels`() {
        assertEquals("Critical", Criticality.CRITICAL.displayName())
        assertEquals("Low", Criticality.LOW.displayName())
    }

    @Test
    fun `bootstrapColor returns correct classes`() {
        assertEquals("danger", Criticality.CRITICAL.bootstrapColor())
        assertEquals("secondary", Criticality.LOW.bootstrapColor())
    }
}

// AssetTest.kt - Add test for effective criticality
@Test
fun `getEffectiveCriticality returns explicit criticality when inheritCriticality is false`() {
    val asset = Asset(
        name = "test-asset",
        type = "server",
        owner = "owner@example.com",
        criticality = Criticality.LOW,
        inheritCriticality = false
    )

    val workgroup = Workgroup(name = "wg1", criticality = Criticality.CRITICAL)
    asset.workgroups.add(workgroup)

    assertEquals(Criticality.LOW, asset.getEffectiveCriticality())
}

@Test
fun `getEffectiveCriticality inherits highest criticality from workgroups`() {
    val asset = Asset(
        name = "test-asset",
        type = "server",
        owner = "owner@example.com",
        inheritCriticality = true
    )

    val wg1 = Workgroup(name = "wg1", criticality = Criticality.MEDIUM)
    val wg2 = Workgroup(name = "wg2", criticality = Criticality.CRITICAL)
    asset.workgroups.addAll(listOf(wg1, wg2))

    assertEquals(Criticality.CRITICAL, asset.getEffectiveCriticality()) // CRITICAL < MEDIUM in ordinal
}
```

**Contract Tests** (REST API):
```kotlin
// WorkgroupControllerContractTest.kt
@Test
fun `PUT workgroups-id-criticality updates criticality`() {
    val workgroup = workgroupService.createWorkgroup("Test WG")

    client.toBlocking().exchange<Any>(
        HttpRequest.PUT("/api/workgroups/${workgroup.id}/criticality",
            mapOf("criticality" to "CRITICAL")),
        String::class.java
    )

    val updated = workgroupRepository.findById(workgroup.id!!).get()
    assertEquals(Criticality.CRITICAL, updated.criticality)
}
```

### API Design Considerations

**RESTful Endpoints**:
```kotlin
// WorkgroupController.kt
@Put("/workgroups/{id}/criticality")
@Secured("ADMIN")
fun updateCriticality(id: Long, @Body request: CriticalityUpdateRequest): Workgroup

@Get("/workgroups/{id}/criticality-impact")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun getCriticalityImpact(id: Long, newCriticality: Criticality?): CriticalityImpact

// AssetController.kt
@Put("/assets/{id}/criticality")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun updateCriticality(id: Long, @Body request: AssetCriticalityUpdateRequest): Asset

@Get("/assets/filter-by-criticality")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun filterByCriticality(criticality: Criticality, pageable: Pageable): Page<Asset>
```

### Database Migration Notes

Hibernate auto-migration (project setting: `jpa.default.properties.hibernate.hbm2ddl.auto=update`) will:
1. Add `criticality VARCHAR(20)` to `workgroup` table
2. Add `criticality VARCHAR(20), inherit_criticality BOOLEAN DEFAULT TRUE` to `asset` table
3. Create indexes automatically from `@Table(indexes = [...])`

**Manual verification SQL** (production checklist):
```sql
-- Check columns added
DESCRIBE workgroup; -- Should show criticality column
DESCRIBE asset;     -- Should show criticality, inherit_criticality columns

-- Check indexes created
SHOW INDEX FROM workgroup WHERE Key_name = 'idx_workgroup_criticality';
SHOW INDEX FROM asset WHERE Key_name IN ('idx_asset_criticality', 'idx_asset_criticality_name');

-- Check default values
SELECT COUNT(*) FROM asset WHERE inherit_criticality = TRUE; -- Should be all rows
```

---

## Summary of Decisions

| Area | Decision | Key Rationale |
|------|----------|---------------|
| **Enum Design** | Standalone `Criticality.kt` | Shared across entities, existing pattern (NotificationType) |
| **Inheritance Logic** | `@Transient` property with getter | No storage overhead, auto-calculated, no sync needed |
| **Bulk Propagation** | None required | Transient calculation eliminates need, zero writes |
| **React Badge** | `CriticalityBadge.tsx` component | WCAG AA compliant, matches SeverityBadge pattern |
| **Performance** | Single + composite indexes | Fast filters (5ms), sorted lists (15ms), 60KB/1000 rows overhead |

**Risk Mitigation**:
- **N+1 Queries**: Workgroups already EAGER-fetched, no additional queries
- **Index Overhead**: Minimal (60KB/1000 rows), worth 10x query speedup
- **Inheritance Complexity**: Simple logic, well-tested, transient = no stale data
- **Accessibility**: Triple redundancy (color + icon + text), WCAG 2.1 AA compliant

**Next Steps** (Implementation Phase):
1. Create `Criticality.kt` enum with helper methods
2. Update `Workgroup.kt` and `Asset.kt` entities
3. Add `getEffectiveCriticality()` method to Asset
4. Create `CriticalityBadge.tsx` component
5. Write unit tests for enum, entities, and inheritance logic
6. Add REST endpoints for CRUD operations
7. Verify Hibernate migrations and indexes
8. Update UI components (workgroup table, asset table, detail views)

**Estimated Implementation Time**: 6-8 hours (TDD approach with full test coverage)
