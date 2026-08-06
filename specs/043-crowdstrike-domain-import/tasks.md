# Implementation Tasks: CrowdStrike Domain Import Enhancement

**Feature**: 043-crowdstrike-domain-import
**Branch**: `043-crowdstrike-domain-import`
**Status**: Ready for Implementation
**Generated**: 2025-11-08

---

## Overview

This document provides an actionable task breakdown for implementing Feature 043: CrowdStrike Domain Import Enhancement. Tasks are organized by user story to enable independent, incremental delivery.

**User Stories**:
- **US1 (P1)**: Domain Capture During Import - Extract and display domain statistics
- **US2 (P2)**: Manual Domain Editing - Enable UI-based domain field editing
- **US3 (P1)**: Smart Update for Existing Assets - Implement field-level comparison logic

**MVP Scope**: User Story 1 (US1) delivers core value - domain capture and statistics

---

## Implementation Strategy

### Independent Delivery

Each user story phase is **independently testable** and can be deployed separately:

1. **US1 (P1)**: Domain capture + statistics → Delivers domain data collection
2. **US3 (P1)**: Smart updates → Enhances import efficiency (can be deployed after US1)
3. **US2 (P2)**: Manual editing → Adds admin convenience (depends on US1 for domain display)

### Parallel Opportunities

Tasks marked `[P]` can be executed in parallel with other `[P]` tasks **within the same phase**.

**Example**: Within US1, T007 [P] (modify ImportResultDto) and T008 [P] (add normalization) can run simultaneously as they modify different files.

### Dependency Graph

```
Phase 1 (Setup)
    ↓
Phase 2 (Foundational)
    ↓
Phase 3 (US1 - Domain Capture) ─┐
    ↓                            │
Phase 4 (US3 - Smart Updates) ←─┘ (Can run in parallel with US1)
    ↓
Phase 5 (US2 - Manual Editing) ← Requires US1 complete
    ↓
Phase 6 (Polish)
```

---

## Phase 1: Setup & Prerequisites

**Goal**: Verify development environment and document structure

- [ ] T001 Verify backend builds successfully with `./gradlew :backendng:build`
- [ ] T002 Verify frontend builds successfully with `cd src/frontend && npm run build`
- [ ] T003 Verify `ad_domain` column exists in asset table with `DESCRIBE asset`
- [ ] T004 Review existing CrowdStrikeVulnerabilityImportService in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt
- [ ] T005 Review existing AssetMergeService in src/backendng/src/main/kotlin/com/secman/service/AssetMergeService.kt

**Completion Criteria**: All verifications pass, code review complete

---

## Phase 2: Foundational Components

**Goal**: Add shared domain normalization logic that all user stories will use

- [ ] T006 [P] Add @PrePersist lifecycle hook to Asset entity for domain normalization in src/backendng/src/main/kotlin/com/secman/domain/Asset.kt

**Implementation**:
```kotlin
@PrePersist
@PreUpdate
fun normalizeDomain() {
    adDomain = adDomain?.trim()?.lowercase()
}
```

**Completion Criteria**: Domain normalization applied on entity save, existing tests pass

---

## Phase 3: User Story 1 - Domain Capture During Import (P1)

**Story Goal**: Extract AD domain from CrowdStrike API, store in asset table, display statistics after import

**Independent Test**: Run CrowdStrike import → verify assets have `ad_domain` populated → verify import summary shows domain count and list

### Backend Tasks

#### 3.1 DTO Modifications

- [ ] T007 [P] [US1] Add `uniqueDomainCount` and `discoveredDomains` fields to ImportResultDto in src/backendng/src/main/kotlin/com/secman/dto/ImportResultDto.kt

**Implementation**:
```kotlin
@Serdeable
data class ImportResultDto(
    val totalAssets: Int,
    val totalVulnerabilities: Int,
    val importedCount: Int,
    val skippedCount: Int,
    val errors: List<String>,
    val uniqueDomainCount: Int = 0,  // ADD
    val discoveredDomains: List<String> = emptyList()  // ADD
)
```

#### 3.2 API Response Mapping

- [ ] T008 [P] [US1] Extract `machine_domain` field from CrowdStrike API response in src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClientImpl.kt

**Location**: `mapResponseToDtos()` method around line 1174

**Implementation**:
```kotlin
val hostInfo = vuln["host_info"] as? Map<*, *>
val hostname = hostInfo?.get("hostname")?.toString() ?: "unknown"
val domain = hostInfo?.get("machine_domain")?.toString()  // ADD
```

- [ ] T009 [US1] Pass domain parameter to AssetMergeService.findOrCreateAsset() in CrowdStrikeVulnerabilityImportService

**Location**: src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt

**Implementation**: Add `adDomain = domain` parameter to findOrCreateAsset() calls

#### 3.3 Domain Statistics Tracking

- [ ] T010 [US1] Add class-level `uniqueDomains` Set to CrowdStrikeVulnerabilityImportService in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt

**Implementation**:
```kotlin
@Singleton
class CrowdStrikeVulnerabilityImportService(...) {
    private val uniqueDomains = mutableSetOf<String>()

    @Transactional
    fun importVulnerabilities(...): ImportResultDto {
        uniqueDomains.clear()  // Reset each import
        // ... existing logic ...
    }
}
```

- [ ] T011 [US1] Track unique domains during asset processing in CrowdStrikeVulnerabilityImportService

**Implementation**: Add after asset creation:
```kotlin
asset.adDomain?.let { domain ->
    uniqueDomains.add(domain.trim().uppercase())
}
```

- [ ] T012 [US1] Populate `uniqueDomainCount` and `discoveredDomains` in ImportResultDto return statement

**Implementation**:
```kotlin
return ImportResultDto(
    // ... existing fields ...
    uniqueDomainCount = uniqueDomains.size,
    discoveredDomains = uniqueDomains.sorted()
)
```

#### 3.4 Controller Enhancement

- [ ] T013 [US1] Update CrowdStrikeController to return enhanced ImportResultDto with domain statistics in src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt

**Note**: No code changes needed if service already returns ImportResultDto - just verify response includes new fields

### Frontend Tasks

- [ ] T014 [P] [US1] Add domain statistics display section to CrowdStrike import results UI in src/frontend/src/components/CrowdStrikeImportResults.tsx

**Implementation**:
```tsx
<h5>Domain Discovery</h5>
<div className="domain-stats">
  <p><strong>Unique Domains:</strong> {result.uniqueDomainCount}</p>
  {result.uniqueDomainCount > 0 && (
    <p><strong>Domains:</strong> {result.discoveredDomains.join(', ')}</p>
  )}
  {result.uniqueDomainCount === 0 && (
    <p className="text-muted">No domains discovered</p>
  )}
</div>
```

- [ ] T015 [P] [US1] Update TypeScript interfaces for ImportResult to include domain statistics in src/frontend/src/types/ImportResult.ts

**Implementation**:
```typescript
interface ImportResult {
  totalAssets: number;
  totalVulnerabilities: number;
  uniqueDomainCount: number;  // ADD
  discoveredDomains: string[];  // ADD
  errors: string[];
}
```

### Story Completion

- [ ] T016 [US1] **Independent Test**: Run CrowdStrike import with test data containing domain information, verify asset table has `ad_domain` populated, verify import summary displays domain count and list

**Test Command**: Import via UI or API, then check:
```sql
SELECT name, ad_domain FROM asset WHERE ad_domain IS NOT NULL LIMIT 10;
```

**Expected**: Domain values stored lowercase, import summary shows "Unique Domains: X" and domain list

---

## Phase 4: User Story 3 - Smart Update for Existing Assets (P1)

**Story Goal**: Only update fields that changed during re-import, prevent data loss

**Independent Test**: Import assets → manually edit some fields → re-import with different domain → verify only domain updated, other fields preserved

**Note**: This story can be implemented in parallel with US1 as they modify different service methods

### Backend Tasks

- [ ] T017 [P] [US3] Add `newAdDomain` parameter to AssetMergeService.mergeAssetData() in src/backendng/src/main/kotlin/com/secman/service/AssetMergeService.kt

**Location**: Method signature around line 81

**Implementation**:
```kotlin
fun mergeAssetData(
    asset: Asset,
    newIp: String?,
    newGroups: String?,
    newAdDomain: String?,  // ADD THIS
    newCloudAccountId: String?,
    // ... other parameters
): Asset {
```

- [ ] T018 [US3] Implement domain field comparison logic in AssetMergeService.mergeAssetData()

**Location**: Inside mergeAssetData() method after existing field comparisons

**Implementation**:
```kotlin
var updated = false

// ... existing field comparisons ...

// Domain comparison
if (newAdDomain != null && newAdDomain.isNotBlank() &&
    newAdDomain.lowercase() != asset.adDomain?.lowercase()) {
    log.debug("Updating AD domain for {}: {} -> {}",
        asset.name, asset.adDomain, newAdDomain)
    asset.adDomain = newAdDomain
    updated = true
}

// ... other field comparisons ...

if (updated) {
    asset.updatedAt = LocalDateTime.now()
    return assetRepository.update(asset)
}

return asset  // No DB write
```

- [ ] T019 [US3] Update CrowdStrikeVulnerabilityImportService to pass domain to AssetMergeService.findOrCreateAsset()

**Location**: src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt

**Implementation**: Ensure domain extracted from API is passed to findOrCreateAsset() which calls mergeAssetData()

### Story Completion

- [ ] T020 [US3] **Independent Test**: Import assets without domain → manually edit asset fields → re-import with domain data → verify only domain field updated, other fields preserved, `updatedAt` only changes if domain changed

**Test Steps**:
1. Import asset "server1" with no domain
2. Manually set server1.owner = "john"
3. Re-import server1 with domain = "contoso"
4. Verify: server1.adDomain = "contoso", server1.owner = "john" (preserved)

---

## Phase 5: User Story 2 - Manual Domain Editing (P2)

**Story Goal**: Enable ADMIN users to manually edit domain field in Asset Management UI

**Independent Test**: Open Asset edit form → verify domain field visible and editable → enter domain → save → verify change persists

**Dependency**: Requires US1 complete (domain field must be displayed in asset data)

### Frontend Tasks

- [ ] T021 [P] [US2] Verify `adDomain` field is visible in Asset edit form in src/frontend/src/components/AssetManagement.tsx

**Check**: Ensure edit form includes domain text input (may already exist from Feature 042)

- [ ] T022 [P] [US2] Add client-side domain validation function to AssetManagement component

**Implementation**:
```tsx
function validateDomain(value: string): boolean {
  if (!value) return true;  // Optional field

  const regex = /^[a-zA-Z0-9.-]+$/;
  if (!regex.test(value)) {
    setDomainError('Domain must contain only letters, numbers, dots, and hyphens');
    return false;
  }

  if (value.startsWith('.') || value.endsWith('.')) {
    setDomainError('Domain cannot start or end with a dot');
    return false;
  }

  setDomainError('');
  return true;
}
```

- [ ] T023 [US2] Add Bootstrap validation styling to domain input field

**Implementation**:
```tsx
<div className="mb-3">
  <label htmlFor="adDomain" className="form-label">
    Active Directory Domain <span className="text-muted">(optional)</span>
  </label>
  <input
    type="text"
    id="adDomain"
    className={`form-control ${domainError ? 'is-invalid' : ''}`}
    value={asset.adDomain || ''}
    onChange={(e) => {
      setAsset({ ...asset, adDomain: e.target.value });
      validateDomain(e.target.value);
    }}
    placeholder="e.g., contoso, sales.domain"
  />
  {domainError && (
    <div className="invalid-feedback">{domainError}</div>
  )}
  <small className="form-text text-muted">
    Enter the Active Directory domain name (alphanumeric, dots, hyphens)
  </small>
</div>
```

- [ ] T024 [US2] Call validation function on domain field blur and submit events

**Implementation**: Add `onBlur={() => validateDomain(asset.adDomain)}` to input, check validation before form submit

### Story Completion

- [ ] T025 [US2] **Independent Test**: Open asset in edit mode → modify domain field → enter invalid domain (e.g., ".contoso") → verify validation error → enter valid domain → save → verify domain updated in database

**Test Steps**:
1. Navigate to Asset Management
2. Click edit on any asset
3. Enter ".invalid" in domain field → see error message
4. Enter "valid-domain" → error clears
5. Click Save → verify success message
6. Reload page → verify domain = "valid-domain"

---

## Phase 6: Polish & Cross-Cutting Concerns

**Goal**: Code quality, documentation, and verification

- [ ] T026 [P] Add inline code comments explaining domain normalization strategy in Asset.kt
- [ ] T027 [P] Add logging statements for domain extraction failures in CrowdStrikeApiClientImpl.kt
- [ ] T028 [P] Update OpenAPI documentation in specs/043-crowdstrike-domain-import/contracts/ to reflect ImportResultDto changes
- [ ] T029 Verify all user stories meet acceptance criteria from spec.md
- [ ] T030 Run full backend build: `./gradlew :backendng:build`
- [ ] T031 Run full frontend build: `cd src/frontend && npm run build`
- [ ] T032 Manual E2E test: Import → Edit → Re-import → Verify all scenarios work end-to-end
- [ ] T033 Update CLAUDE.md with new patterns if any (domain normalization, smart update pattern)

---

## Task Summary

**Total Tasks**: 33

### By Phase
- Phase 1 (Setup): 5 tasks
- Phase 2 (Foundational): 1 task
- Phase 3 (US1): 12 tasks (5 backend, 2 frontend, 1 test)
- Phase 4 (US3): 4 tasks (3 backend, 1 test)
- Phase 5 (US2): 5 tasks (4 frontend, 1 test)
- Phase 6 (Polish): 8 tasks

### By User Story
- **US1 (Domain Capture)**: 12 tasks → **MVP Deliverable**
- **US3 (Smart Updates)**: 4 tasks → Enhances US1
- **US2 (Manual Editing)**: 5 tasks → Depends on US1

### Parallelization
- **Phase 3**: T007-T008, T014-T015 can run in parallel (different files)
- **Phase 4**: Can run in parallel with Phase 3 (different service methods)
- **Phase 5**: T021-T024 can run in parallel (UI tasks)
- **Phase 6**: T026-T028 can run in parallel (documentation)

**Estimated Total**: 7 parallelizable tasks across phases

---

## Implementation Notes

### Testing Note

Per Constitutional Principle IV (User-Requested Testing), formal test case implementation is **deferred until explicitly requested** by the user. However, each user story phase includes an "Independent Test" checklist item that describes manual verification steps to ensure the story works correctly.

If you wish to implement automated tests (JUnit, Playwright), please request test task generation and they will be added to this document.

### MVP First Approach

**Recommended Implementation Order**:
1. Phase 1-2: Setup (T001-T006)
2. Phase 3: US1 Domain Capture (T007-T016) → **MVP Complete**
3. Phase 4: US3 Smart Updates (T017-T020) → Enhances efficiency
4. Phase 5: US2 Manual Editing (T021-T025) → Adds admin convenience
5. Phase 6: Polish (T026-T033)

**MVP Deployment**: After completing Phase 3 (US1), you have a working feature that captures and displays domain information. Deploy this first, then iterate with US3 and US2.

### File Change Summary

**Backend**:
- Modify: Asset.kt (1 method)
- Modify: ImportResultDto.kt (2 fields)
- Modify: CrowdStrikeApiClientImpl.kt (1 line extraction)
- Modify: CrowdStrikeVulnerabilityImportService.kt (domain tracking + statistics)
- Modify: AssetMergeService.kt (domain comparison logic)
- Existing: CrowdStrikeController.kt (no changes if already returns DTO)

**Frontend**:
- Modify: CrowdStrikeImportResults.tsx (domain stats display)
- Modify: AssetManagement.tsx (domain validation)
- Modify: ImportResult type definition (2 fields)

**Total Files Modified**: 8 files
**Lines of Code**: ~150 LOC added

---

## Dependencies Between Stories

```
US1 (Domain Capture)
  ↓
US2 (Manual Editing) ← Requires US1 for domain display

US1 (Domain Capture) ← Can run independently
  ↓
US3 (Smart Updates) ← Enhances US1, but independent functionality
```

**Key Insight**: US1 and US3 are both P1 and can be implemented in parallel as they modify different parts of the codebase. US2 depends on US1 being complete.

---

## Success Validation

After completing all tasks, verify these success criteria from spec.md:

- [ ] **SC-001**: CrowdStrike import captures domain for ≥95% of assets with domain data
- [ ] **SC-002**: Import summary displays domain statistics in <1 second
- [ ] **SC-003**: Manual domain edit reflects immediately without page refresh
- [ ] **SC-004**: Re-imports complete 30% faster due to smart updates
- [ ] **SC-005**: Zero data loss during re-imports
- [ ] **SC-006**: 100% of imports display domain statistics (even if zero)

---

**End of Tasks Document**
