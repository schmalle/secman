# Quickstart Guide: Asset and Workgroup Criticality Classification

**Feature**: 039-asset-workgroup-criticality
**Date**: 2025-11-01
**For**: Developers implementing the criticality classification feature

## Overview

This guide provides a step-by-step implementation path for adding criticality classification to workgroups and assets. Follow the TDD workflow: write tests first, implement features, refactor.

## Prerequisites

- [ ] Spec reviewed (`spec.md`)
- [ ] Data model reviewed (`data-model.md`)
- [ ] API contracts reviewed (`contracts/*.yaml`)
- [ ] Research document reviewed (`research.md`)
- [ ] Development environment running (MariaDB, backend, frontend)

## Implementation Roadmap

### Phase 1: Backend Domain Layer (2-3 hours)

#### Step 1.1: Create Criticality Enum (30 min)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/Criticality.kt`

**Test First** (`src/backendng/src/test/kotlin/com/secman/domain/CriticalityTest.kt`):

```kotlin
package com.secman.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CriticalityTest {
    @Test
    fun `ordinal values should represent priority order`() {
        assertTrue(Criticality.CRITICAL.ordinal < Criticality.LOW.ordinal)
    }

    @Test
    fun `isHigherThan should return true for CRITICAL vs MEDIUM`() {
        assertTrue(Criticality.CRITICAL.isHigherThan(Criticality.MEDIUM))
    }

    @Test
    fun `isHigherThan should return false for LOW vs HIGH`() {
        assertFalse(Criticality.LOW.isHigherThan(Criticality.HIGH))
    }

    @Test
    fun `bootstrapColor should return correct Bootstrap class`() {
        assertEquals("danger", Criticality.CRITICAL.bootstrapColor())
        assertEquals("warning", Criticality.HIGH.bootstrapColor())
        assertEquals("info", Criticality.MEDIUM.bootstrapColor())
        assertEquals("secondary", Criticality.LOW.bootstrapColor())
    }

    @Test
    fun `icon should return correct Bootstrap icon identifier`() {
        assertEquals("exclamation-triangle-fill", Criticality.CRITICAL.icon())
    }
}
```

**Run tests** (they should fail):
```bash
./gradlew test --tests CriticalityTest
```

**Implement**:
```kotlin
enum class Criticality {
    CRITICAL, HIGH, MEDIUM, LOW;

    fun displayName(): String = name

    fun bootstrapColor(): String = when(this) {
        CRITICAL -> "danger"
        HIGH -> "warning"
        MEDIUM -> "info"
        LOW -> "secondary"
    }

    fun icon(): String = when(this) {
        CRITICAL -> "exclamation-triangle-fill"
        HIGH -> "exclamation-circle-fill"
        MEDIUM -> "info-circle-fill"
        LOW -> "check-circle-fill"
    }

    fun isHigherThan(other: Criticality): Boolean = this.ordinal < other.ordinal
}
```

**Run tests** (they should pass):
```bash
./gradlew test --tests CriticalityTest
```

---

#### Step 1.2: Add Criticality to Workgroup Entity (30 min)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`

**Test First** (`src/backendng/src/test/kotlin/com/secman/domain/WorkgroupTest.kt`):

```kotlin
@Test
fun `new workgroup should default to MEDIUM criticality`() {
    val workgroup = Workgroup(name = "test")
    assertEquals(Criticality.MEDIUM, workgroup.criticality)
}

@Test
fun `workgroup criticality should be modifiable`() {
    val workgroup = Workgroup(name = "test", criticality = Criticality.CRITICAL)
    assertEquals(Criticality.CRITICAL, workgroup.criticality)
}
```

**Run tests** (they should fail):
```bash
./gradlew test --tests WorkgroupTest
```

**Implement**: Add field to Workgroup entity:

```kotlin
@Enumerated(EnumType.STRING)
@Column(name = "criticality", nullable = false, length = 20)
var criticality: Criticality = Criticality.MEDIUM
```

**Database Migration**: Hibernate auto-migration will create the column on next startup.

**Run tests** (they should pass):
```bash
./gradlew test --tests WorkgroupTest
```

---

#### Step 1.3: Add Criticality to Asset Entity (45 min)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**Test First** (`src/backendng/src/test/kotlin/com/secman/domain/AssetTest.kt`):

```kotlin
@Test
fun `asset criticality should default to null`() {
    val asset = Asset(name = "test", type = "Server", owner = "admin")
    assertNull(asset.criticality)
}

@Test
fun `effectiveCriticality should return explicit criticality when set`() {
    val asset = Asset(
        name = "test",
        type = "Server",
        owner = "admin",
        criticality = Criticality.HIGH
    )
    assertEquals(Criticality.HIGH, asset.effectiveCriticality)
}

@Test
fun `effectiveCriticality should inherit from workgroup when null`() {
    val workgroup = Workgroup(name = "prod", criticality = Criticality.CRITICAL)
    val asset = Asset(name = "test", type = "Server", owner = "admin")
    asset.workgroups.add(workgroup)

    assertEquals(Criticality.CRITICAL, asset.effectiveCriticality)
}

@Test
fun `effectiveCriticality should use highest workgroup criticality`() {
    val wg1 = Workgroup(name = "low", criticality = Criticality.LOW)
    val wg2 = Workgroup(name = "critical", criticality = Criticality.CRITICAL)
    val wg3 = Workgroup(name = "medium", criticality = Criticality.MEDIUM)
    val asset = Asset(name = "test", type = "Server", owner = "admin")
    asset.workgroups.addAll(listOf(wg1, wg2, wg3))

    assertEquals(Criticality.CRITICAL, asset.effectiveCriticality)
}

@Test
fun `effectiveCriticality should default to MEDIUM when no workgroups`() {
    val asset = Asset(name = "test", type = "Server", owner = "admin")
    assertEquals(Criticality.MEDIUM, asset.effectiveCriticality)
}
```

**Run tests** (they should fail):
```bash
./gradlew test --tests AssetTest
```

**Implement**: Add fields to Asset entity:

```kotlin
@Enumerated(EnumType.STRING)
@Column(name = "criticality", nullable = true, length = 20)
var criticality: Criticality? = null

@Transient
val effectiveCriticality: Criticality
    get() = criticality
        ?: workgroups.maxByOrNull { it.criticality.ordinal }?.criticality
        ?: Criticality.MEDIUM
```

**Database Migration**: Hibernate auto-migration will create the nullable column.

**Run tests** (they should pass):
```bash
./gradlew test --tests AssetTest
```

---

### Phase 2: Backend Service Layer (1-2 hours)

#### Step 2.1: Update WorkgroupService (30 min)

**Test First** (`src/backendng/src/test/kotlin/com/secman/service/WorkgroupServiceTest.kt`):

```kotlin
@Test
fun `updateWorkgroup should persist criticality change`() {
    val workgroup = workgroupService.create(
        CreateWorkgroupRequest(name = "test", criticality = Criticality.MEDIUM)
    )
    val updated = workgroupService.update(
        workgroup.id!!,
        UpdateWorkgroupRequest(criticality = Criticality.HIGH)
    )
    assertEquals(Criticality.HIGH, updated.criticality)
}
```

**Implement**: Update `UpdateWorkgroupRequest` DTO and service logic.

**Run tests**:
```bash
./gradlew test --tests WorkgroupServiceTest
```

---

#### Step 2.2: Update AssetService (30 min)

**Test First** (`src/backendng/src/test/kotlin/com/secman/service/AssetServiceTest.kt`):

```kotlin
@Test
fun `createAsset should allow explicit criticality override`() {
    val asset = assetService.create(
        CreateAssetRequest(
            name = "test",
            type = "Server",
            owner = "admin",
            criticality = Criticality.CRITICAL
        )
    )
    assertEquals(Criticality.CRITICAL, asset.criticality)
    assertEquals(Criticality.CRITICAL, asset.effectiveCriticality)
}

@Test
fun `updateAsset should allow clearing criticality to revert to inheritance`() {
    val workgroup = workgroupService.create(
        CreateWorkgroupRequest(name = "prod", criticality = Criticality.HIGH)
    )
    val asset = assetService.create(
        CreateAssetRequest(
            name = "test",
            type = "Server",
            owner = "admin",
            criticality = Criticality.CRITICAL,
            workgroupIds = listOf(workgroup.id!!)
        )
    )

    // Clear explicit criticality
    val updated = assetService.update(
        asset.id!!,
        UpdateAssetRequest(criticality = null)
    )

    assertNull(updated.criticality)
    assertEquals(Criticality.HIGH, updated.effectiveCriticality)
}
```

**Implement**: Update `CreateAssetRequest`, `UpdateAssetRequest` DTOs and service logic.

**Run tests**:
```bash
./gradlew test --tests AssetServiceTest
```

---

### Phase 3: Backend Controller Layer (1 hour)

#### Step 3.1: Update WorkgroupController (30 min)

**Contract Test First** (`src/backendng/src/test/kotlin/com/secman/controller/WorkgroupControllerTest.kt`):

```kotlin
@Test
fun `POST workgroups should create workgroup with criticality`() {
    val request = mapOf(
        "name" to "test",
        "criticality" to "CRITICAL"
    )
    val response = client.toBlocking().exchange(
        HttpRequest.POST("/api/workgroups", request),
        Workgroup::class.java
    )
    assertEquals(HttpStatus.CREATED, response.status)
    assertEquals(Criticality.CRITICAL, response.body().criticality)
}

@Test
fun `PUT workgroups should update criticality`() {
    val workgroup = createTestWorkgroup(criticality = Criticality.MEDIUM)
    val updateRequest = mapOf("criticality" to "HIGH")

    val response = client.toBlocking().exchange(
        HttpRequest.PUT("/api/workgroups/${workgroup.id}", updateRequest),
        Workgroup::class.java
    )

    assertEquals(HttpStatus.OK, response.status)
    assertEquals(Criticality.HIGH, response.body().criticality)
}

@Test
fun `PUT workgroups with invalid criticality should return 400`() {
    val workgroup = createTestWorkgroup()
    val updateRequest = mapOf("criticality" to "INVALID")

    val exception = assertThrows<HttpClientResponseException> {
        client.toBlocking().exchange(
            HttpRequest.PUT("/api/workgroups/${workgroup.id}", updateRequest),
            Workgroup::class.java
        )
    }
    assertEquals(HttpStatus.BAD_REQUEST, exception.status)
}
```

**Implement**: Update WorkgroupController to handle criticality in request/response.

**Run tests**:
```bash
./gradlew test --tests WorkgroupControllerTest
```

---

#### Step 3.2: Update AssetController (30 min)

**Contract Test First** (`src/backendng/src/test/kotlin/com/secman/controller/AssetControllerTest.kt`):

```kotlin
@Test
fun `POST assets should create asset with explicit criticality`() {
    val request = mapOf(
        "name" to "test",
        "type" to "Server",
        "owner" to "admin",
        "criticality" to "CRITICAL"
    )

    val response = client.toBlocking().exchange(
        HttpRequest.POST("/api/assets", request),
        Asset::class.java
    )

    assertEquals(HttpStatus.CREATED, response.status)
    assertEquals(Criticality.CRITICAL, response.body().criticality)
    assertEquals(Criticality.CRITICAL, response.body().effectiveCriticality)
}

@Test
fun `GET assets should include effectiveCriticality in response`() {
    val workgroup = createTestWorkgroup(criticality = Criticality.HIGH)
    val asset = createTestAsset(
        name = "test",
        criticality = null,
        workgroupIds = listOf(workgroup.id!!)
    )

    val response = client.toBlocking().retrieve(
        "/api/assets/${asset.id}",
        Asset::class.java
    )

    assertNull(response.criticality)
    assertEquals(Criticality.HIGH, response.effectiveCriticality)
}

@Test
fun `GET assets with criticality filter should return only matching assets`() {
    createTestAsset(criticality = Criticality.CRITICAL)
    createTestAsset(criticality = Criticality.MEDIUM)

    val response = client.toBlocking().retrieve(
        "/api/assets?criticality=CRITICAL",
        Argument.listOf(Asset::class.java)
    )

    assertEquals(1, response.size)
    assertEquals(Criticality.CRITICAL, response[0].criticality)
}
```

**Implement**: Update AssetController to handle criticality filtering and include effectiveCriticality in responses.

**Run tests**:
```bash
./gradlew test --tests AssetControllerTest
```

---

### Phase 4: Frontend Components (2-3 hours)

#### Step 4.1: Create CriticalityBadge Component (45 min)

**File**: `src/frontend/src/components/CriticalityBadge.tsx`

```tsx
import React from 'react';

interface CriticalityBadgeProps {
  criticality: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | null | undefined;
  inherited?: boolean;
  sourceWorkgroup?: string;
  compact?: boolean;
}

const CriticalityBadge: React.FC<CriticalityBadgeProps> = ({
  criticality,
  inherited = false,
  sourceWorkgroup,
  compact = false
}) => {
  const level = criticality || 'MEDIUM';

  const colorMap = {
    CRITICAL: 'danger',
    HIGH: 'warning',
    MEDIUM: 'info',
    LOW: 'secondary'
  };

  const iconMap = {
    CRITICAL: 'bi-exclamation-triangle-fill',
    HIGH: 'bi-exclamation-circle-fill',
    MEDIUM: 'bi-info-circle-fill',
    LOW: 'bi-check-circle-fill'
  };

  const color = colorMap[level];
  const icon = iconMap[level];

  const title = inherited && sourceWorkgroup
    ? `Inherited from ${sourceWorkgroup}`
    : inherited
    ? 'Inherited from workgroup'
    : 'Explicitly set';

  return (
    <span
      className={`badge bg-${color} bg-opacity-${inherited ? '25' : '100'}`}
      title={title}
      aria-label={`Criticality: ${level}${inherited ? ' (inherited)' : ''}`}
    >
      <i className={`bi ${icon}`} aria-hidden="true"></i>
      {!compact && <span className="ms-1">{level}</span>}
      {inherited && !compact && <small className="ms-1">(inherited)</small>}
    </span>
  );
};

export default CriticalityBadge;
```

**Test** (manual in browser):
```bash
cd src/frontend
npm run dev
# Navigate to a page using the badge component
```

---

#### Step 4.2: Update WorkgroupManagement Component (45 min)

**File**: `src/frontend/src/components/WorkgroupManagement.tsx`

**Changes**:
1. Add criticality dropdown to create/edit modal
2. Add CriticalityBadge to table display
3. Add criticality filter control
4. Add criticality sort capability

**Implementation**:

```tsx
// In modal form (create/edit)
<div className="mb-3">
  <label htmlFor="criticality" className="form-label">Criticality Level</label>
  <select
    className="form-select"
    id="criticality"
    value={formData.criticality || 'MEDIUM'}
    onChange={(e) => setFormData({ ...formData, criticality: e.target.value })}
    required
  >
    <option value="CRITICAL">CRITICAL</option>
    <option value="HIGH">HIGH</option>
    <option value="MEDIUM">MEDIUM</option>
    <option value="LOW">LOW</option>
  </select>
  <div className="form-text">
    Baseline criticality for all assets in this workgroup (unless overridden)
  </div>
</div>

// In table display
<td>
  <CriticalityBadge criticality={workgroup.criticality} />
</td>

// In filter controls
<select
  className="form-select"
  value={filters.criticality || ''}
  onChange={(e) => setFilters({ ...filters, criticality: e.target.value || null })}
>
  <option value="">All Criticality Levels</option>
  <option value="CRITICAL">CRITICAL</option>
  <option value="HIGH">HIGH</option>
  <option value="MEDIUM">MEDIUM</option>
  <option value="LOW">LOW</option>
</select>
```

**Test** (manual in browser):
- Create workgroup with CRITICAL level → verify badge displays
- Change workgroup to HIGH → verify badge updates
- Filter by CRITICAL → verify only CRITICAL workgroups shown
- Sort by criticality → verify CRITICAL appears first

---

#### Step 4.3: Update AssetManagement Component (1-1.5 hours)

**File**: `src/frontend/src/components/AssetManagement.tsx`

**Changes**:
1. Add criticality dropdown (with "Inherit from workgroup" option) to create/edit modal
2. Add CriticalityBadge with inheritance indicator to table display
3. Add criticality filter control (both explicit and effective)
4. Add criticality sort capability
5. Add progress indicator for workgroup criticality updates

**Implementation**:

```tsx
// In modal form (create/edit)
<div className="mb-3">
  <label htmlFor="criticality" className="form-label">Criticality Level</label>
  <select
    className="form-select"
    id="criticality"
    value={formData.criticality || ''}
    onChange={(e) => setFormData({ ...formData, criticality: e.target.value || null })}
  >
    <option value="">Inherit from workgroup</option>
    <option value="CRITICAL">CRITICAL (override)</option>
    <option value="HIGH">HIGH (override)</option>
    <option value="MEDIUM">MEDIUM (override)</option>
    <option value="LOW">LOW (override)</option>
  </select>
  <div className="form-text">
    Leave blank to inherit from workgroup, or select to override
  </div>
</div>

// In table display
<td>
  <CriticalityBadge
    criticality={asset.effectiveCriticality}
    inherited={asset.criticality === null}
    sourceWorkgroup={asset.criticality === null && asset.workgroups?.length > 0
      ? asset.workgroups.find(wg => wg.criticality === asset.effectiveCriticality)?.name
      : undefined}
  />
</td>

// In filter controls
<select
  className="form-select"
  value={filters.effectiveCriticality || ''}
  onChange={(e) => setFilters({ ...filters, effectiveCriticality: e.target.value || null })}
>
  <option value="">All Criticality Levels</option>
  <option value="CRITICAL">CRITICAL</option>
  <option value="HIGH">HIGH</option>
  <option value="MEDIUM">MEDIUM</option>
  <option value="LOW">LOW</option>
</select>
```

**Test** (manual in browser + Playwright):
- Create asset without criticality → verify inherits from workgroup
- Create asset with CRITICAL override → verify badge shows CRITICAL (override)
- Change workgroup criticality → verify inherited assets update
- Filter by effective criticality → verify both explicit and inherited assets shown

---

### Phase 5: Integration & Dashboard Updates (1-2 hours)

#### Step 5.1: Update OutdatedAssetsDashboard (30 min)

**File**: `src/frontend/src/components/OutdatedAssetsDashboard.tsx`

**Changes**:
1. Add criticality filter dropdown
2. Display criticality badge in asset list
3. Add criticality statistics (X CRITICAL, Y HIGH, Z MEDIUM, W LOW)

---

#### Step 5.2: Notification Integration (Feature 035) (1 hour)

**File**: `src/backendng/src/main/kotlin/com/secman/service/NotificationService.kt`

**Test First**:

```kotlin
@Test
fun `sendNewVulnerabilityNotification should send immediate notification for CRITICAL assets`() {
    val asset = createTestAsset(criticality = Criticality.CRITICAL)
    val vuln = createTestVulnerability(asset)

    notificationService.sendNewVulnerabilityNotification(vuln)

    // Verify notification sent within 1 hour (immediate queue)
    verify { emailSender.send(any()) }
}

@Test
fun `sendNewVulnerabilityNotification should use standard schedule for MEDIUM assets`() {
    val asset = createTestAsset(criticality = Criticality.MEDIUM)
    val vuln = createTestVulnerability(asset)

    notificationService.sendNewVulnerabilityNotification(vuln)

    // Verify notification uses standard notification schedule
    verify { notificationScheduler.scheduleNotification(any(), delay = standardDelay) }
}
```

**Implement**: Add criticality-based routing logic to notification service.

---

### Phase 6: Database Indexes (15 min)

**Manual SQL** (if Hibernate doesn't auto-create indexes):

```sql
-- Connect to MariaDB
mysql -u root -p secman

-- Add indexes for performance
CREATE INDEX idx_workgroup_criticality ON workgroup(criticality);
CREATE INDEX idx_workgroup_criticality_name ON workgroup(criticality, name);
CREATE INDEX idx_asset_criticality ON asset(criticality);
CREATE INDEX idx_asset_criticality_name ON asset(criticality, name);

-- Verify indexes
SHOW INDEX FROM workgroup;
SHOW INDEX FROM asset;
```

---

## Testing Checklist

### Backend

- [ ] Unit tests for Criticality enum (helper methods)
- [ ] Unit tests for Workgroup entity (default value, validation)
- [ ] Unit tests for Asset entity (inheritance logic, null handling, multiple workgroups)
- [ ] Service tests for WorkgroupService (create/update with criticality)
- [ ] Service tests for AssetService (create/update with criticality, inheritance)
- [ ] Contract tests for WorkgroupController (POST, PUT, GET with criticality)
- [ ] Contract tests for AssetController (POST, PUT, GET with criticality, filtering)
- [ ] Integration test for notification criticality routing

### Frontend

- [ ] CriticalityBadge component renders correctly (all 4 levels)
- [ ] CriticalityBadge shows inheritance indicator
- [ ] WorkgroupManagement create modal includes criticality dropdown
- [ ] WorkgroupManagement table displays criticality badges
- [ ] WorkgroupManagement filter by criticality works
- [ ] AssetManagement create modal includes criticality dropdown with "inherit" option
- [ ] AssetManagement table displays criticality badges with inheritance indicator
- [ ] AssetManagement filter by effective criticality works
- [ ] OutdatedAssetsDashboard shows criticality filter

### E2E (Playwright)

- [ ] Create workgroup with CRITICAL → verify badge displays
- [ ] Update workgroup criticality → verify badge updates
- [ ] Create asset without criticality → verify inherits from workgroup
- [ ] Create asset with CRITICAL override → verify badge shows override
- [ ] Change workgroup criticality → verify assets without override update
- [ ] Filter assets by effective criticality → verify both explicit and inherited shown

---

## Performance Verification

```bash
# Backend performance test
./gradlew test --tests PerformanceTest

# Expected:
# - Filter 10,000 assets by criticality: <2 seconds
# - Compute effective criticality for 1,000 assets: <200ms
# - Update workgroup criticality (affecting 1,000 assets): <5 seconds
```

---

## Deployment Checklist

### Pre-Deployment

- [ ] All tests passing (backend + frontend)
- [ ] Constitution check passed (RBAC, schema evolution, API-first)
- [ ] Database migration tested in dev environment
- [ ] Performance benchmarks met (filtering, sorting, propagation)
- [ ] Accessibility tested (color-blind mode, screen reader)
- [ ] Code review completed
- [ ] Branch merged to main

### Deployment Steps

1. **Stop Application** (if needed for zero-downtime deployment, use rolling update)
2. **Backup Database**:
   ```bash
   mysqldump -u root -p secman > backup_$(date +%Y%m%d).sql
   ```
3. **Deploy Backend** (Hibernate auto-migration will run)
4. **Verify Migration**:
   ```sql
   DESCRIBE workgroup; -- Check for criticality column
   DESCRIBE asset;     -- Check for criticality column
   SELECT COUNT(*) FROM workgroup WHERE criticality = 'MEDIUM'; -- Should match total workgroups
   ```
5. **Deploy Frontend**
6. **Smoke Test**:
   - Login as ADMIN
   - Create workgroup with CRITICAL → verify success
   - Create asset with override → verify success
   - Filter by criticality → verify results
   - Change workgroup criticality → verify propagation

### Post-Deployment

- [ ] Monitor logs for errors
- [ ] Verify database indexes created
- [ ] Verify performance metrics (filter response time <2s)
- [ ] Notify users of new criticality feature

---

## Troubleshooting

### Issue: Migration fails with "column already exists"

**Solution**: Manually drop column and re-run migration:
```sql
ALTER TABLE workgroup DROP COLUMN criticality;
ALTER TABLE asset DROP COLUMN criticality;
```

### Issue: Assets show incorrect effective criticality

**Diagnosis**: Check workgroup relationships:
```sql
SELECT a.id, a.name, a.criticality, GROUP_CONCAT(wg.name, ':', wg.criticality)
FROM asset a
LEFT JOIN asset_workgroups awg ON a.id = awg.asset_id
LEFT JOIN workgroup wg ON awg.workgroup_id = wg.id
GROUP BY a.id;
```

### Issue: Performance slow when filtering by effective criticality

**Solution**: Effective criticality requires service-layer filtering. For better performance, add materialized view or use explicit criticality where possible.

---

## Support

- **Spec**: `specs/039-asset-workgroup-criticality/spec.md`
- **Data Model**: `specs/039-asset-workgroup-criticality/data-model.md`
- **API Contracts**: `specs/039-asset-workgroup-criticality/contracts/`
- **Research**: `specs/039-asset-workgroup-criticality/research.md`

For questions, contact the development team or review the CLAUDE.md file for common patterns.
