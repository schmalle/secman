# Implementation Tasks: CrowdStrike Asset Auto-Creation

**Feature**: 030-crowdstrike-asset-auto-create
**Branch**: `030-crowdstrike-asset-auto-create`
**Date**: 2025-10-19

## Overview

This document contains dependency-ordered implementation tasks for Feature 030. Tasks are organized by user story to enable independent implementation and testing.

**User Note**: User explicitly requested "implement no tests", so test tasks have been omitted from this implementation plan.

## Implementation Strategy

**MVP Scope**: User Story 1 (P1) - Complete save functionality with asset auto-creation
**Incremental Delivery**: US1 → US2 (validation) → US3 (audit verification) → Polish

**Estimated Duration**: ~3-4 hours (without tests)

## Task Summary

- **Total Tasks**: 18
- **Foundational**: 3 tasks (shared infrastructure)
- **User Story 1 (P1)**: 12 tasks (core save functionality)
- **User Story 2 (P2)**: 1 task (validation only)
- **User Story 3 (P3)**: 1 task (validation only)
- **Polish**: 3 tasks (logging, build verification)
- **Parallelizable**: 2 tasks marked with [P]

---

## Phase 1: Setup

**No setup tasks required** - Feature extends existing codebase with no new dependencies or project structure changes.

---

## Phase 2: Foundational Tasks

**Goal**: Implement shared repository methods and DTO changes required by all user stories.

**Dependencies**: None (can start immediately)

**Tasks**:

- [x] T001 Add case-insensitive hostname lookup to src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt
- [x] T002 Add duplicate vulnerability detection to src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt
- [x] T003 Add vulnerabilitiesSkipped field to src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeSaveResponse.kt

### Task Details

**T001**: Add `findByNameIgnoreCase(name: String): Asset?` method declaration to AssetRepository interface. Micronaut Data will automatically generate the implementation.

**T002**: Add `existsByAssetAndVulnerabilityIdAndScanTimestamp(asset: Asset, vulnerabilityId: String, scanTimestamp: LocalDateTime): Boolean` method to VulnerabilityRepository interface.

**T003**: Add `val vulnerabilitiesSkipped: Int` field to CrowdStrikeSaveResponse data class.

**Completion Criteria**: All three repository methods declared, DTO field added, backend compiles without errors.

---

## Phase 3: User Story 1 (P1) - Save CrowdStrike Vulnerabilities with Auto-Created Assets

**Story Goal**: Enable users to save CrowdStrike vulnerability query results to the database with automatic asset creation when the hostname doesn't exist.

**Why this priority**: Core functionality that enables users to transition from CrowdStrike query results to persistent vulnerability tracking.

**Independent Test**: Query CrowdStrike for a system not in the database, click "Save to Database", verify both asset and vulnerabilities are created with correct attributes (hostname, type=Server, owner=current user, no workgroups, IP from CrowdStrike if available).

**Dependencies**:
- Requires: Phase 2 (Foundational Tasks) complete
- Blocks: US2, US3 (later stories depend on US1 working)

**Tasks**:

- [x] T004 [US1] Add @Transactional annotation to saveToDatabase() method in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
- [x] T005 [US1] Update saveToDatabase() method signature to accept Authentication parameter in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
- [x] T006 [US1] Implement validateVulnerability() method with CVE format, severity, and numeric validation in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
- [x] T007 [US1] Update findOrCreateAsset() to set owner=username, type=Server, manualCreator=User entity in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
- [x] T008 [US1] Add IP address update logic for existing assets in findOrCreateAsset() in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
- [x] T009 [US1] Implement duplicate detection using existsByAssetAndVulnerabilityIdAndScanTimestamp() before saving vulnerabilities in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
- [x] T010 [US1] Update saveToDatabase() main logic to validate all vulnerabilities first, skip invalid ones, track skipped count in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
- [x] T011 [US1] Add formatErrorMessage() method for role-based error verbosity in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
- [x] T012 [US1] Update saveVulnerabilities() endpoint to pass Authentication parameter to service in src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt
- [x] T013 [US1] Update controller error handling to use role-based messages in src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt
- [x] T014 [P] [US1] Update success message display to show saved, skipped, and created counts in src/frontend/src/components/CrowdStrikeVulnerabilityLookup.tsx
- [x] T015 [P] [US1] Update error display to show errors list with details in src/frontend/src/components/CrowdStrikeVulnerabilityLookup.tsx

### Task Details

**T004**: Add `@Transactional` annotation above the `saveToDatabase()` method to ensure atomic asset creation and vulnerability saves.

**T005**: Change method signature from `fun saveToDatabase(request: CrowdStrikeSaveRequest)` to `fun saveToDatabase(request: CrowdStrikeSaveRequest, authentication: Authentication)`.

**T006**: Create private method `validateVulnerability(vuln: CrowdStrikeVulnerabilityDto): ValidationResult` that checks:
- CVE ID matches pattern `CVE-\d{4}-\d{4,}` (if present)
- Severity in set {Critical, High, Medium, Low, Informational}
- Days open is numeric if present

**T007**: Modify `findOrCreateAsset()` method:
- Change `type` from "Endpoint" to "Server"
- Set `owner` to `authentication.name` instead of "CrowdStrike"
- Lookup user: `userRepository.findByUsername(authentication.name)`
- Set `manualCreator` to found user entity
- Keep `workgroups` as empty set

**T008**: In `findOrCreateAsset()`, after finding existing asset, add logic:
```kotlin
if (ip != null && asset.ip != ip) {
    logger.info("Updating asset IP: name={}, oldIp={}, newIp={}", asset.name, asset.ip, ip)
    asset.ip = ip
    asset.updatedAt = LocalDateTime.now()
    assetRepository.update(asset)
}
```

**T009**: Before saving each vulnerability, check:
```kotlin
val isDuplicate = vulnerabilityRepository.existsByAssetAndVulnerabilityIdAndScanTimestamp(
    asset, vuln.cveId, vuln.detectedAt
)
if (isDuplicate) {
    skippedCount++
    errors.add("Skipped duplicate: ${vuln.cveId} already exists...")
    continue
}
```

**T010**: Restructure `saveToDatabase()` to:
1. Validate all vulnerabilities first (filter to validVulnerabilities list)
2. Track invalid in skippedCount and errors list
3. Process only valid vulnerabilities
4. Return updated response with vulnerabilitiesSkipped field

**T011**: Add method:
```kotlin
private fun formatErrorMessage(exception: Exception, authentication: Authentication): String {
    val isAdmin = authentication.roles.contains("ADMIN")
    return if (isAdmin) exception.message ?: "Unknown error" else "Database error"
}
```

**T012**: In `CrowdStrikeController.saveVulnerabilities()`, add `authentication: Authentication` parameter and pass it to service: `service.saveToDatabase(request, authentication)`.

**T013**: Update controller catch block to check `authentication.roles.contains("ADMIN")` and format message accordingly (user-friendly vs technical details).

**T014**: In `CrowdStrikeVulnerabilityLookup.tsx`, update success message logic to build message from `vulnerabilitiesSaved`, `vulnerabilitiesSkipped`, and `assetsCreated` fields.

**T015**: In `CrowdStrikeVulnerabilityLookup.tsx`, enhance error display to iterate over `data.errors` array and show each error as a list item.

**Completion Criteria**:
- User can query CrowdStrike, click "Save to Database", and see:
  - New asset created with hostname, type=Server, owner=current user, no workgroups
  - All valid vulnerabilities saved and linked to asset
  - Invalid vulnerabilities skipped with count shown
  - Success message shows "Saved X vulnerabilities, skipped Y, created 1 asset for HOSTNAME"
- Existing assets updated with new IP if different
- Duplicate vulnerabilities skipped (same CVE + same scan date)
- Same CVE with different scan date creates new record (scan history maintained)

---

## Phase 4: User Story 2 (P2) - View Saved Assets in Asset Management

**Story Goal**: Validate that auto-created assets integrate seamlessly with existing asset management UI.

**Why this priority**: Ensures auto-created assets are indistinguishable from manually created ones in the management interface.

**Independent Test**: After saving CrowdStrike vulnerabilities (US1), navigate to /assets and verify the new asset appears with correct metadata. Click into asset detail to see vulnerabilities.

**Dependencies**:
- Requires: US1 complete
- Blocks: None (independent validation)

**Tasks**:

- [ ] T016 [US2] Verify auto-created assets display correctly in Asset Management UI at /assets with hostname, type, owner, and empty workgroups

### Task Details

**T016**: No code changes required. Verification task:
1. Navigate to `/assets` page
2. Confirm auto-created asset appears in list
3. Verify columns show: hostname as name, "Server" as type, username as owner, empty/None for workgroups
4. Click into asset detail view
5. Verify vulnerabilities section shows all saved vulnerabilities with CVE IDs, severity, scan dates

**Completion Criteria**: Auto-created assets and their vulnerabilities are fully visible and manageable through existing Asset Management UI with no distinguishing characteristics.

---

## Phase 5: User Story 3 (P3) - Track Asset Creator for Audit Trail

**Story Goal**: Verify that the system records which user imported each asset from CrowdStrike for audit purposes.

**Why this priority**: Audit trail enhancement, not critical for core functionality but important for governance.

**Independent Test**: Query database or check asset detail view to verify manualCreator field is populated with the authenticated user who saved the vulnerabilities.

**Dependencies**:
- Requires: US1 complete (manualCreator set in T007)
- Blocks: None (verification only)

**Tasks**:

- [ ] T017 [US3] Verify manualCreator field is populated on auto-created assets by checking Asset entity or database

### Task Details

**T017**: No code changes required. Verification task:
1. Save CrowdStrike vulnerabilities as user "testuser"
2. Query database: `SELECT id, name, owner, manual_creator_id FROM assets WHERE name = 'TEST-HOSTNAME'`
3. Verify `owner` = "testuser"
4. Verify `manual_creator_id` is NOT NULL and references User table
5. Join with users table to confirm manualCreator.username = "testuser"

**Completion Criteria**: 100% of auto-created assets have manualCreator field populated with the User entity of the authenticated user who saved them (SC-002).

---

## Phase 6: Polish & Cross-Cutting Concerns

**Goal**: Add comprehensive logging and verify builds.

**Dependencies**: Requires US1 complete

**Tasks**:

- [x] T018 Add INFO-level logging for asset creation, asset updates, vulnerability saves, and validation failures throughout src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt
- [x] T019 Build backend and verify compilation with ./gradlew build in src/backendng/
- [x] T020 Build frontend and verify compilation with npm run build in src/frontend/

### Task Details

**T018**: Add structured logging statements:
- Asset creation: `logger.info("Created asset: name={}, ip={}, owner={}, user={}", hostname, ip, username, username)`
- Asset update: `logger.info("Updating asset IP: name={}, oldIp={}, newIp={}, user={}", ...)`
- Vulnerability save summary: `logger.info("Saved vulnerabilities: asset={}, saved={}, skipped={}, user={}", ...)`
- Validation failure: `logger.warn("Skipped invalid vulnerability: cve={}, reason={}, hostname={}", ...)`
- Errors: `logger.error("Failed to save vulnerabilities: hostname={}, user={}, error={}", ..., e)`

**T019**: Run `./gradlew clean build` and verify no compilation errors.

**T020**: Run `npm run build` and verify no TypeScript or build errors.

**Completion Criteria**:
- All save operations logged with context (SC-007: 100% logging coverage)
- Backend builds successfully
- Frontend builds successfully

---

## Dependencies & Execution Order

### Story Completion Order

```
Phase 2 (Foundational)
  ↓
Phase 3 (US1 - P1) ← CORE FUNCTIONALITY (MVP)
  ↓
Phase 4 (US2 - P2) ← Validation
  ↓
Phase 5 (US3 - P3) ← Verification
  ↓
Phase 6 (Polish)
```

### Task Dependencies

**Sequential Dependencies**:
- T001, T002, T003 (Foundational) MUST complete before T004-T015 (US1)
- T004-T013 (Backend US1) MUST complete before T014-T015 (Frontend US1)
- T016 (US2) requires T004-T015 (US1) complete
- T017 (US3) requires T007 (manualCreator implementation) complete
- T018 (Logging) should happen after T004-T013 (main logic implemented)

**Parallel Opportunities**:
- T014 and T015 can run in parallel (different sections of same file)

---

## Parallel Execution Examples

### Within User Story 1

After T013 (backend) completes, T014 and T015 (frontend) can run concurrently:

**Terminal 1**:
```bash
# Implement T014: Update success message display
code src/frontend/src/components/CrowdStrikeVulnerabilityLookup.tsx
# Modify success message logic around line 110
```

**Terminal 2**:
```bash
# Implement T015: Update error display
code src/frontend/src/components/CrowdStrikeVulnerabilityLookup.tsx
# Modify error display logic in different section (around line 50)
```

These tasks modify different parts of the same file and can be done simultaneously with careful merge.

---

## Success Criteria Validation

After completing all tasks, verify the following success criteria from spec.md:

- [ ] **SC-001**: Save time <5 seconds for 100 vulnerabilities
- [ ] **SC-002**: 100% user attribution (owner + manualCreator populated)
- [ ] **SC-003**: Zero duplicate assets (case-insensitive hostname matching works)
- [ ] **SC-004**: Vulnerabilities viewable in existing management UI
- [ ] **SC-005**: 99.9% transactional integrity (@Transactional ensures rollback on error)
- [ ] **SC-006**: User feedback <1 second (button disables immediately)
- [ ] **SC-007**: 100% operation logging (all operations logged with context)

---

## Manual Testing Checklist

**After US1 implementation**:

1. [ ] **New Asset Creation**: Query hostname not in DB → Save → Verify asset created with correct attributes
2. [ ] **Existing Asset Update**: Query hostname already in DB → Save → Verify IP updated, vulnerabilities added
3. [ ] **Validation**: Mock invalid CVE data → Save → Verify skipped count and error messages
4. [ ] **Duplicate Prevention**: Save same query twice → Second save shows skipped duplicates
5. [ ] **Role-Based Errors**: Cause error as USER → See generic message; Cause error as ADMIN → See technical details
6. [ ] **Scan History**: Save same CVE on different dates → Verify multiple records created

**After US2 validation**:

7. [ ] Navigate to /assets → Verify auto-created asset appears in list
8. [ ] Click asset detail → Verify vulnerabilities displayed

**After US3 verification**:

9. [ ] Check database or asset metadata → Verify manualCreator field populated

---

## Notes

- **No tests included**: User explicitly requested "implement no tests", so all test tasks have been omitted. TDD principle is not followed in this implementation.
- **Transaction boundary**: @Transactional at service method level ensures atomic asset + vulnerability saves
- **Error handling**: Role-based error messages (user-friendly for regular users, technical details for ADMIN)
- **Logging**: INFO level for operations, WARN for validation failures, ERROR for exceptions
- **Performance**: Existing schema and indexes sufficient for <5s save time target (SC-001)
- **Schema changes**: None required (extends existing entities)

---

## Quick Reference

**Key Files Modified**:
1. `AssetRepository.kt` - Add findByNameIgnoreCase()
2. `VulnerabilityRepository.kt` - Add duplicate detection method
3. `CrowdStrikeSaveResponse.kt` - Add vulnerabilitiesSkipped field
4. `CrowdStrikeVulnerabilityService.kt` - Main save logic updates (8 tasks)
5. `CrowdStrikeController.kt` - Pass authentication, role-based errors (2 tasks)
6. `CrowdStrikeVulnerabilityLookup.tsx` - Success/error message updates (2 tasks)

**Functional Requirements Coverage**:
- FR-001 to FR-018: All covered by US1 tasks
- No missing requirements

**Estimated Timeline**:
- Foundational: 20 minutes (T001-T003)
- US1 Backend: 1.5 hours (T004-T013)
- US1 Frontend: 20 minutes (T014-T015)
- US2/US3 Verification: 20 minutes (T016-T017)
- Polish: 30 minutes (T018-T020)
- **Total: ~3-4 hours**
