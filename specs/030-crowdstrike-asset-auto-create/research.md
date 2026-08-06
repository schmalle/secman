# Research: CrowdStrike Asset Auto-Creation

**Date**: 2025-10-19
**Feature**: 030-crowdstrike-asset-auto-create

## Overview

Research findings for extending the existing CrowdStrike vulnerability save functionality to automatically create and update assets with proper user attribution, type assignment, and enhanced validation.

## Existing CrowdStrike Integration Analysis

### Current Architecture

The `/vulnerabilities/system` page already implements a complete CrowdStrike Falcon API integration:

**Data Flow**:
1. User enters hostname → Frontend `CrowdStrikeVulnerabilityLookup.tsx`
2. Query sent to `GET /api/crowdstrike/vulnerabilities?hostname=X`
3. `CrowdStrikeController.kt` → `CrowdStrikeVulnerabilityService.kt`
4. Service performs:
   - OAuth2 authentication with CrowdStrike Falcon
   - Device ID lookup (`/devices/queries/devices/v1`)
   - Vulnerability query (`/spotlight/combined/vulnerabilities/v1`)
   - Exception checking against database
5. Results displayed in sortable table
6. **"Save to Database" button triggers `POST /api/crowdstrike/vulnerabilities/save`**

**Key Finding**: The save infrastructure already exists but has critical gaps for Feature 030.

### Data Availability

**CrowdStrikeVulnerabilityDto Structure**:
```kotlin
data class CrowdStrikeVulnerabilityDto(
    val hostname: String,              // ✅ Asset name
    val ip: String?,                   // ✅ Asset IP (nullable)
    val cveId: String?,                // ✅ CVE identifier
    val severity: String,              // ✅ Severity level
    val affectedProduct: String?,      // ✅ Vulnerable product/version
    val daysOpen: String?,             // ✅ Days open
    val detectedAt: LocalDateTime,     // ✅ Scan timestamp
    val hasException: Boolean,         // ✅ Exception status
    // ... other fields
)
```

**Decision**: All required fields for Feature 030 are already extracted from the CrowdStrike API. No additional API calls or data mapping needed.

### Current Save Implementation Gaps

**Analyzed**: `CrowdStrikeVulnerabilityService.saveToDatabase()` (lines 728-778)

**Critical Gaps**:

1. **Asset Owner** (FR-002):
   - Current: Hardcoded as `"CrowdStrike"`
   - Required: Current authenticated user's username
   - Fix: Pass `Authentication` from controller to service

2. **Asset Type** (FR-003):
   - Current: `"Endpoint"`
   - Required: `"Server"`
   - Fix: Change constant in asset creation

3. **Manual Creator** (FR-005):
   - Current: Not set
   - Required: `User` entity reference for audit trail
   - Fix: Lookup user by username, set `manualCreator` field

4. **Case-Insensitive Matching** (FR-006):
   - Current: `assetRepository.findByName(hostname)` (case-sensitive)
   - Required: Case-insensitive to prevent duplicates
   - Fix: Add `findByNameIgnoreCase()` to `AssetRepository`

5. **IP Address Update** (FR-007):
   - Current: IP never updated for existing assets
   - Required: Update if CrowdStrike provides different value
   - Fix: Add update logic in `findOrCreateAsset()`

6. **Validation** (FR-017):
   - Current: No validation of CVE format, severity values, numeric fields
   - Required: Skip invalid vulnerabilities, report counts
   - Fix: Add validation logic before save, collect skipped items

7. **Duplicate Detection** (FR-011):
   - Current: No check for exact duplicates
   - Required: Prevent duplicate (asset + CVE ID + scan date)
   - Fix: Check before save, increment skipped counter

8. **Transactions** (FR-012):
   - Current: No explicit transaction management
   - Required: Atomic asset creation + vulnerability saves
   - Fix: Add `@Transactional` annotation

9. **Logging** (FR-018):
   - Current: Minimal logging
   - Required: Log asset creation/update, save counts, validation failures, errors
   - Fix: Add structured logging throughout save flow

10. **Error Messages** (FR-015):
    - Current: Same messages for all users
    - Required: Role-based verbosity (user-friendly vs. technical)
    - Fix: Check user roles, format messages accordingly

11. **Success Message Enhancement** (FR-014):
    - Current: Reports `vulnerabilitiesSaved` and `assetsCreated`
    - Required: Also report `vulnerabilitiesSkipped`
    - Fix: Add skipped counter to response DTO

## Technical Decisions

### Decision 1: Modify Existing Service vs. Create New Service

**Chosen**: Modify existing `CrowdStrikeVulnerabilityService.saveToDatabase()` method

**Rationale**:
- Service already handles asset creation and vulnerability saves
- Save endpoint, DTOs, and frontend integration exist
- Changes are enhancement, not replacement
- Reduces code duplication
- Maintains backward compatibility (response structure extensible)

**Alternatives Considered**:
- Create new `CrowdStrikeAssetAutoCreateService` → Rejected: Would duplicate 90% of existing logic
- Create separate endpoint → Rejected: Frontend already integrated with existing endpoint

### Decision 2: Case-Insensitive Hostname Lookup Strategy

**Chosen**: Add `findByNameIgnoreCase(name: String): Asset?` to `AssetRepository`

**Rationale**:
- Micronaut Data supports `IgnoreCase` suffix for case-insensitive queries
- Database-level case-insensitive comparison more efficient than application-level
- Prevents duplicate assets with different casing (e.g., "SERVER1" vs "server1")
- Simple, declarative approach

**Alternatives Considered**:
- Normalize to lowercase before save → Rejected: Loses original casing, breaks existing data
- Use `LOWER(name)` in custom query → Rejected: Less readable than Micronaut Data convention

### Decision 3: Vulnerability Validation Strategy

**Chosen**: Validate before save, collect invalid items, continue with valid ones

**Rationale**:
- Aligns with existing CSV import pattern (Feature 016) where invalid rows are skipped
- Maximizes successful saves rather than all-or-nothing approach
- Provides user feedback on what was skipped and why
- CrowdStrike API may return incomplete/malformed data occasionally

**Validation Rules**:
```kotlin
fun isValidVulnerability(vuln: CrowdStrikeVulnerabilityDto): Boolean {
    // CVE ID: Must match pattern CVE-YYYY-NNNNN (if present)
    if (vuln.cveId != null && !vuln.cveId.matches(Regex("CVE-\\d{4}-\\d{4,}"))) {
        return false
    }

    // Severity: Must be recognized value
    val validSeverities = setOf("Critical", "High", "Medium", "Low", "Informational")
    if (vuln.severity !in validSeverities) {
        return false
    }

    // Days open: Must be numeric if present
    if (vuln.daysOpen != null && vuln.daysOpen.filter { it.isDigit() }.toIntOrNull() == null) {
        return false
    }

    return true
}
```

**Alternatives Considered**:
- Reject entire save if any invalid → Rejected: Too strict, loses valid data
- Save all, flag invalid → Rejected: Pollutes database with bad data

### Decision 4: Transaction Boundary

**Chosen**: Apply `@Transactional` at service method level (`saveToDatabase()`)

**Rationale**:
- Ensures atomic operation: if vulnerability save fails, asset creation rolls back
- Micronaut's `@Transactional` integrates with Hibernate JPA
- Service layer is appropriate boundary for business transaction
- Existing repository methods inherit transaction context

**Transaction Scope**:
- Asset lookup/creation
- All vulnerability saves
- User lookup for manualCreator
- Rollback on any repository exception

**Alternatives Considered**:
- Controller-level transaction → Rejected: Service layer is proper business logic boundary
- Repository-level transaction → Rejected: Too granular, doesn't span multiple saves

### Decision 5: User Context Propagation

**Chosen**: Pass `Authentication` object from controller to service method

**Method Signature Change**:
```kotlin
// Before
fun saveToDatabase(request: CrowdStrikeSaveRequest): CrowdStrikeSaveResponse

// After
fun saveToDatabase(request: CrowdStrikeSaveRequest, authentication: Authentication): CrowdStrikeSaveResponse
```

**Rationale**:
- Controller has access to `Authentication` via Micronaut security
- Service remains testable (mock authentication in tests)
- Explicit dependency makes user requirement clear
- Avoids global context or thread-local access

**User Information Extracted**:
- `authentication.name` → Asset owner (username string)
- User lookup via `userRepository.findByUsername()` → manualCreator (User entity)
- `authentication.roles` → Role-based error message formatting

**Alternatives Considered**:
- Inject `SecurityService` into service → Rejected: Adds unnecessary dependency
- Use `@CurrentUser` parameter injection → Rejected: Requires additional configuration

### Decision 6: Duplicate Vulnerability Detection

**Chosen**: Check existence before save using unique constraint (asset + CVE ID + scan date)

**Implementation**:
```kotlin
// Check if exact duplicate exists
val isDuplicate = vulnerabilityRepository.existsByAssetAndVulnerabilityIdAndScanTimestamp(
    asset = asset,
    vulnerabilityId = vuln.cveId,
    scanTimestamp = vuln.detectedAt
)

if (isDuplicate) {
    skippedCount++
    errors.add("Duplicate: ${vuln.cveId} already exists for ${asset.name} on ${vuln.detectedAt}")
    continue  // Skip this vulnerability
}
```

**Rationale**:
- Database unique constraint prevents duplicates at persistence layer
- Explicit check before save avoids constraint violation exceptions
- Allows reporting of skipped duplicates to user
- Supports scan history tracking (same CVE, different dates = new record)

**Alternatives Considered**:
- Rely on database constraint alone → Rejected: Throws exception, harder to report to user
- Update existing record → Rejected: Loses scan history per clarification answer

### Decision 7: Logging Strategy

**Chosen**: Structured logging with SLF4J at INFO level for operations, ERROR for failures

**Log Events**:
```kotlin
// Asset creation
logger.info("Created asset: name={}, ip={}, owner={}, user={}", hostname, ip, username, username)

// Asset update
logger.info("Updated asset IP: name={}, oldIp={}, newIp={}, user={}", asset.name, asset.ip, ip, username)

// Vulnerability save
logger.info("Saved vulnerabilities: asset={}, saved={}, skipped={}, user={}", hostname, savedCount, skippedCount, username)

// Validation failure
logger.warn("Skipped invalid vulnerability: cve={}, reason={}, hostname={}", cveId, reason, hostname)

// Errors
logger.error("Failed to save vulnerabilities: hostname={}, user={}, error={}", hostname, username, e.message, e)
```

**Rationale**:
- Provides audit trail for compliance (who created what, when)
- Enables troubleshooting (why was vulnerability skipped?)
- Structured format supports log aggregation/analysis
- INFO level doesn't overwhelm logs but captures key operations

**Alternatives Considered**:
- DEBUG level → Rejected: Would be disabled in production, lose audit trail
- TRACE level for all validations → Rejected: Too verbose for 100+ vulnerabilities

### Decision 8: Role-Based Error Message Formatting

**Chosen**: Check `authentication.roles` and format messages accordingly

**Implementation**:
```kotlin
fun formatErrorMessage(baseMessage: String, technicalDetail: String?, authentication: Authentication): String {
    val isAdmin = authentication.roles.contains("ADMIN")
    return if (isAdmin && technicalDetail != null) {
        "$baseMessage: $technicalDetail"
    } else {
        baseMessage
    }
}

// Usage
val message = formatErrorMessage(
    baseMessage = "Failed to save vulnerabilities",
    technicalDetail = "Foreign key constraint violation on asset_id=123",
    authentication = authentication
)
```

**Rationale**:
- Balances security (no sensitive details to regular users) with debuggability (admins get full context)
- Simple role check (ADMIN vs. others)
- Applies to error responses and log messages
- Aligns with existing RBAC patterns

**Alternatives Considered**:
- Always show technical details → Rejected: Security risk, information disclosure
- Never show technical details → Rejected: Makes troubleshooting impossible
- Per-field visibility control → Rejected: Over-engineered for this use case

## Integration Points

### Backend Changes Required

**Files to Modify**:
1. `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityService.kt`
   - Update `saveToDatabase()` signature to accept `Authentication`
   - Modify `findOrCreateAsset()` to set owner, type, manualCreator
   - Add IP update logic
   - Add validation logic
   - Add duplicate detection
   - Add role-based error formatting
   - Add comprehensive logging
   - Add `@Transactional` annotation

2. `src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt`
   - Update `saveVulnerabilities()` to pass `authentication` to service

3. `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`
   - Add `findByNameIgnoreCase(name: String): Asset?` method

4. `src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt`
   - Add `existsByAssetAndVulnerabilityIdAndScanTimestamp()` method (if not already present)

5. `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeSaveResponse.kt`
   - Add `vulnerabilitiesSkipped: Int` field

**Files to Create**:
- None (all changes are modifications to existing files)

### Frontend Changes Required

**Files to Modify**:
1. `src/frontend/src/components/CrowdStrikeVulnerabilityLookup.tsx`
   - Update success message display to show `vulnerabilitiesSkipped` count
   - Enhance error message display (already shows backend errors)

**Files to Create**:
- None (button and service integration already exist)

### Test Changes Required

**Files to Create**:
1. `src/backendng/src/test/kotlin/com/secman/contract/CrowdStrikeSaveContractTest.kt`
   - Test save with new asset creation
   - Test save with existing asset (IP update)
   - Test validation (skip invalid vulnerabilities)
   - Test duplicate detection
   - Test role-based error messages
   - Test authentication required

2. `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilitySaveServiceTest.kt`
   - Unit tests for validation logic
   - Unit tests for IP update logic
   - Unit tests for case-insensitive matching
   - Unit tests for duplicate detection

3. `src/frontend/tests/e2e/crowdstrike-save.spec.ts`
   - E2E test for save workflow
   - Verify asset creation
   - Verify success message with counts

## Performance Considerations

**Query Performance**:
- Case-insensitive hostname lookup: Database index on LOWER(name) may help (optional optimization)
- Duplicate detection: Existing composite index on (asset_id, vulnerability_id, scan_timestamp) sufficient
- User lookup: Indexed by username (primary identifier)

**Transaction Size**:
- Batch size: Up to 100 vulnerabilities per save operation (per SC-001)
- Transaction duration: <5 seconds target (SC-001)
- Rollback impact: Acceptable for error cases (99.9% success per SC-005)

**Logging Volume**:
- One log entry per asset operation (create/update)
- One summary log entry per save operation
- One log entry per validation failure
- Estimated: 5-10 log entries per save operation (reasonable)

## Summary

**Research Status**: ✅ Complete

**Key Findings**:
1. ✅ All required infrastructure exists (endpoint, service, DTOs, UI button)
2. ✅ All required data available from CrowdStrike API (hostname, IP, CVE, severity, etc.)
3. ✅ Changes are enhancements to existing service, not new implementation
4. ✅ No schema changes required (existing entities support all requirements)
5. ✅ Performance goals achievable with existing architecture

**Unknowns Resolved**:
1. ✅ Current save implementation gaps identified
2. ✅ Technical approach validated (modify existing service)
3. ✅ Data availability confirmed
4. ✅ Integration points mapped
5. ✅ No architectural blockers

**Ready for Phase 1**: Data model design and API contracts
