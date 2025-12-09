# Tasks: CrowdStrike Import Cleanup

**Input**: Design documents from `/specs/053-crowdstrike-import-cleanup/`
**Prerequisites**: plan.md (complete), spec.md (complete), research.md (complete)

**Type**: Bug Fix - Implementation already complete, verification and commit required

**Tests**: Not required per Constitution Principle IV (User-Requested Testing)

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Verification

**Purpose**: Verify the fix resolves the duplicate vulnerability issue

- [ ] T001 Build the backend to ensure code compiles: `./gradlew build` in src/backendng/
- [ ] T002 [US1] Verify Domain Vulnerabilities view shows correct counts (matches CrowdStrike Lookup)
- [ ] T003 [US1] Verify re-import of same servers replaces vulnerabilities (no duplicates)
- [ ] T004 [US2] Verify vulnerability count changes are trackable between imports

**Checkpoint**: All user stories verified working correctly

---

## Phase 2: Commit Changes

**Purpose**: Commit the bug fix with proper documentation

- [ ] T005 Review changes in VulnerabilityRepository.kt: `git diff src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt`
- [ ] T006 Review changes in DomainVulnsService.kt: `git diff src/backendng/src/main/kotlin/com/secman/service/DomainVulnsService.kt`
- [ ] T007 Stage and commit changes with descriptive message

**Commit message template**:
```
fix(domain-vulns): Filter to latest import per asset in Domain Vulnerabilities view

Root cause: DomainVulnsService was using findAll() which loaded ALL vulnerabilities
including historical imports, causing inflated counts.

Fix: Added findLatestVulnerabilitiesForAssetIds() query that filters to only the
latest importTimestamp per asset using a native SQL subquery.

Closes: #053
```

---

## Phase 3: Polish

**Purpose**: Final cleanup and documentation

- [ ] T008 [P] Update CLAUDE.md if new patterns were introduced
- [ ] T009 Verify gradlew build passes with no errors: `./gradlew build`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Verification)**: Start immediately - code already modified
- **Phase 2 (Commit)**: Depends on verification passing
- **Phase 3 (Polish)**: Depends on commit

### User Story Mapping

| Task | Story | Purpose |
|------|-------|---------|
| T002 | US1 | Clean Import - verify no duplicates in view |
| T003 | US1 | Clean Import - verify replace behavior |
| T004 | US2 | Remediation Tracking - verify counts change correctly |

Note: US3 (Import Atomicity) was already implemented in Feature 048 and verified during investigation.

---

## Implementation Summary

This bug fix consists of 2 file changes (already implemented):

| File | Change | Lines |
|------|--------|-------|
| `VulnerabilityRepository.kt` | Added `findLatestVulnerabilitiesForAssetIds()` | +28 |
| `DomainVulnsService.kt` | Use new query instead of `findAll()` | +5/-2 |

### What Was Fixed

**Before (Buggy)**:
```kotlin
val allVulnerabilities = vulnerabilityRepository.findAll().toList()
    .filter { vuln -> assetIds.contains(vuln.asset.id) }
```

**After (Fixed)**:
```kotlin
val allVulnerabilities = if (assetIds.isNotEmpty()) {
    vulnerabilityRepository.findLatestVulnerabilitiesForAssetIds(assetIds.toSet())
} else {
    emptyList()
}
```

---

## Notes

- Implementation is already complete - these tasks are for verification and commit only
- No new features added - this is a query-side bug fix
- Transactional replace pattern (Feature 048) was confirmed working correctly
- Root cause was in how DomainVulnsService queried vulnerabilities, not in the import process
