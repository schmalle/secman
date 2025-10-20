# Data Model: CrowdStrike Asset Auto-Creation

**Feature**: 030-crowdstrike-asset-auto-create
**Date**: 2025-10-19

## Overview

This feature extends the existing data model without schema changes. All required fields exist in the current `Asset` and `Vulnerability` entities. Changes are behavioral (how entities are populated and updated) rather than structural.

## Entity Relationships

```
User (existing)
  ↓ (1:N - manualCreator)
Asset (existing - no schema changes)
  ↓ (1:N)
Vulnerability (existing - no schema changes)
```

## Entities

### Asset (Existing - Behavioral Changes Only)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**Existing Fields** (no changes to schema):
```kotlin
@Entity
@Table(name = "assets")
data class Asset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String,                   // Hostname from CrowdStrike

    @Column
    var type: String? = null,           // Changed from "Endpoint" → "Server"

    @Column
    var ip: String? = null,             // Updated if CrowdStrike provides different value

    @Column
    var owner: String? = null,          // Changed from "CrowdStrike" → current username

    @Column
    var description: String? = null,    // Remains null/empty for auto-created assets

    @ManyToOne
    @JoinColumn(name = "manual_creator_id")
    var manualCreator: User? = null,    // NOW POPULATED for auto-created assets

    @ManyToMany
    @JoinTable(
        name = "asset_workgroups",
        joinColumns = [JoinColumn(name = "asset_id")],
        inverseJoinColumns = [JoinColumn(name = "workgroup_id")]
    )
    var workgroups: MutableSet<Workgroup> = mutableSetOf(),  // Remains empty

    @OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL])
    var vulnerabilities: MutableList<Vulnerability> = mutableListOf(),

    @Column(name = "last_seen")
    var lastSeen: LocalDateTime? = null,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
```

**Behavioral Changes for Feature 030**:

| Field | Current Behavior | New Behavior (Feature 030) | Requirement |
|-------|-----------------|---------------------------|-------------|
| `name` | Hostname from CrowdStrike | Same (no change) | FR-001 |
| `type` | Hardcoded `"Endpoint"` | Hardcoded `"Server"` | FR-003 |
| `ip` | Set on creation, never updated | Updated if CrowdStrike provides different value for existing assets | FR-007 |
| `owner` | Hardcoded `"CrowdStrike"` | Current authenticated user's username | FR-002 |
| `description` | Empty string | Null/empty (sensible default) | FR-016 |
| `manualCreator` | Not set (null) | Current authenticated `User` entity | FR-005 |
| `workgroups` | Empty set | Remain empty (no assignments) | FR-004 |
| `lastSeen` | Current timestamp | Same (no change) | - |

**Case-Insensitive Lookup** (FR-006):
- Existing: `AssetRepository.findByName(hostname)` (case-sensitive)
- New: `AssetRepository.findByNameIgnoreCase(hostname)` (case-insensitive)
- Prevents duplicates: "SERVER1" vs "server1" vs "Server1" → same asset

**Update Logic** (FR-007):
```kotlin
// Pseudo-code for IP update behavior
if (existingAsset != null) {
    if (crowdStrikeIp != null && existingAsset.ip != crowdStrikeIp) {
        logger.info("Updating asset IP: name={}, oldIp={}, newIp={}",
                    existingAsset.name, existingAsset.ip, crowdStrikeIp)
        existingAsset.ip = crowdStrikeIp
        existingAsset.updatedAt = LocalDateTime.now()
        assetRepository.update(existingAsset)
    }
}
```

### Vulnerability (Existing - Behavioral Changes Only)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/Vulnerability.kt`

**Existing Fields** (no changes to schema):
```kotlin
@Entity
@Table(
    name = "vulnerabilities",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_vulnerability_asset_cve_scan",
            columnNames = ["asset_id", "vulnerability_id", "scan_timestamp"]
        )
    ],
    indexes = [
        Index(name = "idx_vulnerability_asset", columnList = "asset_id"),
        Index(name = "idx_vulnerability_scan", columnList = "scan_timestamp")
    ]
)
data class Vulnerability(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    var asset: Asset,                   // Link to Asset (new or existing)

    @Column(name = "vulnerability_id")
    var vulnerabilityId: String,        // CVE ID from CrowdStrike

    @Column(name = "cvss_severity")
    var cvssSeverity: String,           // Severity from CrowdStrike

    @Column(name = "vulnerable_product_versions", length = 1000)
    var vulnerableProductVersions: String? = null,  // Product from CrowdStrike

    @Column(name = "days_open")
    var daysOpen: Int? = null,          // Days open from CrowdStrike

    @Column(name = "scan_timestamp")
    var scanTimestamp: LocalDateTime,   // Detected timestamp from CrowdStrike

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now()
)
```

**Behavioral Changes for Feature 030**:

| Field | Mapping Source | Validation | Requirement |
|-------|---------------|------------|-------------|
| `asset` | Created/found asset | Must exist | FR-009 |
| `vulnerabilityId` | `CrowdStrikeVulnerabilityDto.cveId` | CVE format: `CVE-YYYY-NNNNN+` | FR-010, FR-017 |
| `cvssSeverity` | `CrowdStrikeVulnerabilityDto.severity` | Must be in valid set | FR-010, FR-017 |
| `vulnerableProductVersions` | `CrowdStrikeVulnerabilityDto.affectedProduct` | None (optional) | FR-010 |
| `daysOpen` | Parse integer from `CrowdStrikeVulnerabilityDto.daysOpen` | Must be numeric if present | FR-010, FR-017 |
| `scanTimestamp` | `CrowdStrikeVulnerabilityDto.detectedAt` | Must be valid datetime | FR-010 |

**Duplicate Prevention** (FR-011):
```kotlin
// Unique constraint enforces: (asset_id, vulnerability_id, scan_timestamp)
// Behavior:
// - Same asset + same CVE + same scan date → SKIP (duplicate)
// - Same asset + same CVE + different scan date → NEW RECORD (scan history)

// Example:
// Asset: "SERVER1"
// CVE: "CVE-2024-1234"
// Scan 1 (2025-10-15): Creates record #1
// Scan 2 (2025-10-15, re-save): SKIPPED (exact duplicate)
// Scan 3 (2025-10-20): Creates record #2 (different scan date = new observation)
```

**Validation Logic** (FR-017):
```kotlin
data class ValidationResult(
    val isValid: Boolean,
    val reason: String? = null
)

fun validateVulnerability(vuln: CrowdStrikeVulnerabilityDto): ValidationResult {
    // CVE ID format
    if (vuln.cveId != null && !vuln.cveId.matches(Regex("CVE-\\d{4}-\\d{4,}"))) {
        return ValidationResult(false, "Invalid CVE ID format: ${vuln.cveId}")
    }

    // Severity value
    val validSeverities = setOf("Critical", "High", "Medium", "Low", "Informational")
    if (vuln.severity !in validSeverities) {
        return ValidationResult(false, "Invalid severity: ${vuln.severity}")
    }

    // Days open (numeric)
    if (vuln.daysOpen != null) {
        val daysValue = vuln.daysOpen.filter { it.isDigit() }.toIntOrNull()
        if (daysValue == null || daysValue < 0) {
            return ValidationResult(false, "Invalid days open: ${vuln.daysOpen}")
        }
    }

    return ValidationResult(true)
}
```

### User (Existing - Read-Only Usage)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Usage in Feature 030**:
- **Read-only**: No modifications to User entity
- **Lookup**: `userRepository.findByUsername(authentication.name)`
- **Purpose**: Populate `Asset.manualCreator` for audit trail (FR-005)

**Required Fields**:
- `username`: String (unique)
- `email`: String
- `roles`: Set<String>

## Repository Changes

### AssetRepository

**File**: `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`

**New Method** (FR-006):
```kotlin
@Repository
interface AssetRepository : JpaRepository<Asset, Long> {

    // Existing methods...
    fun findByName(name: String): Asset?

    // NEW: Case-insensitive hostname lookup
    fun findByNameIgnoreCase(name: String): Asset?

    // Existing methods...
    fun findByIp(ip: String): Asset?
}
```

**Rationale**: Micronaut Data automatically implements case-insensitive query using `LOWER()` function based on `IgnoreCase` suffix.

### VulnerabilityRepository

**File**: `src/backendng/src/main/kotlin/com/secman/repository/VulnerabilityRepository.kt`

**New Method** (FR-011 - if not already present):
```kotlin
@Repository
interface VulnerabilityRepository : JpaRepository<Vulnerability, Long> {

    // Existing methods...

    // NEW: Check for exact duplicate before save
    fun existsByAssetAndVulnerabilityIdAndScanTimestamp(
        asset: Asset,
        vulnerabilityId: String,
        scanTimestamp: LocalDateTime
    ): Boolean

    // Alternative: Query method if exists check not available
    fun findByAssetAndVulnerabilityIdAndScanTimestamp(
        asset: Asset,
        vulnerabilityId: String,
        scanTimestamp: LocalDateTime
    ): Vulnerability?
}
```

**Rationale**: Prevents duplicate constraint violations by checking before save, enables user-friendly error reporting.

### UserRepository

**File**: `src/backendng/src/main/kotlin/com/secman/repository/UserRepository.kt`

**Existing Method** (no changes):
```kotlin
@Repository
interface UserRepository : JpaRepository<User, Long> {
    fun findByUsername(username: String): User?
    // ... other methods
}
```

**Usage**: Lookup user by authenticated username to populate `Asset.manualCreator`.

## Data Flow

### Save Operation Flow

```
1. Frontend: User clicks "Save to Database"
   ↓
2. Frontend: POST /api/crowdstrike/vulnerabilities/save
   Body: { hostname, vulnerabilities: [...] }
   ↓
3. Controller: Extract Authentication object
   ↓
4. Service: saveToDatabase(request, authentication)
   ↓
5. Service: Validate all vulnerabilities (skip invalid, collect valid)
   ↓
6. Service: Find or create asset
   6a. AssetRepository.findByNameIgnoreCase(hostname)
   6b. If not found:
       - Create new Asset
       - Set type = "Server"
       - Set owner = authentication.name
       - Lookup user: userRepository.findByUsername(authentication.name)
       - Set manualCreator = user
       - Set workgroups = empty
       - Set ip = crowdStrikeIp (if available)
   6c. If found:
       - Update ip if different (from CrowdStrike)
       - Keep existing owner, type, manualCreator, workgroups
   ↓
7. Service: For each valid vulnerability
   7a. Check duplicate: vulnerabilityRepository.existsByAssetAndVulnerabilityIdAndScanTimestamp(...)
   7b. If duplicate: Skip, increment skippedCount
   7c. If not duplicate:
       - Create Vulnerability entity
       - Link to asset
       - Save via repository
       - Increment savedCount
   ↓
8. Service: Return response
   - vulnerabilitiesSaved: savedCount
   - vulnerabilitiesSkipped: skippedCount + invalidCount
   - assetsCreated: 0 or 1
   - errors: [...validation/duplicate messages...]
   ↓
9. Frontend: Display success/error message with counts
```

### Transaction Boundary

```
@Transactional  // Applied to saveToDatabase() method
{
    User lookup (read-only)
    Asset find/create (write)
    Asset update if existing (write)
    For each vulnerability:
        Duplicate check (read-only)
        Vulnerability create (write)

    // If ANY write operation fails → ROLLBACK entire transaction
    // User sees error, database unchanged
}
```

## State Transitions

### Asset Lifecycle (Feature 030 Context)

```
[Non-existent]
    ↓ (User clicks "Save" for hostname not in DB)
[Created via CrowdStrike]
    - type: "Server"
    - owner: {username}
    - manualCreator: {User entity}
    - workgroups: []
    - ip: {from CrowdStrike or null}
    ↓ (Future saves for same hostname)
[Updated]
    - ip updated if CrowdStrike provides different value
    - Other fields unchanged
    - New vulnerabilities added
```

### Vulnerability Lifecycle

```
[Scan Result from CrowdStrike]
    ↓ (Validation passes)
[Valid Vulnerability]
    ↓ (Duplicate check: NOT exists with same asset+CVE+scanDate)
[Saved to Database]
    - Linked to asset
    - Scan history maintained

[Scan Result from CrowdStrike]
    ↓ (Validation fails: invalid CVE format or severity)
[Invalid Vulnerability]
    ↓ (Skipped)
[Reported in errors list]
    - Not saved
    - User sees reason in response

[Scan Result from CrowdStrike]
    ↓ (Duplicate check: EXISTS with same asset+CVE+scanDate)
[Duplicate Vulnerability]
    ↓ (Skipped)
[Counted in vulnerabilitiesSkipped]
    - Not saved (would violate unique constraint)
    - Same CVE + different scan date → creates NEW record (scan history)
```

## Constraints and Indexes

### Existing Constraints (Leveraged by Feature 030)

**Vulnerability Table**:
```sql
-- Unique constraint prevents exact duplicates
CONSTRAINT uk_vulnerability_asset_cve_scan
    UNIQUE (asset_id, vulnerability_id, scan_timestamp)

-- Indexes support performance
INDEX idx_vulnerability_asset (asset_id)
INDEX idx_vulnerability_scan (scan_timestamp)
```

**Asset Table**:
- No unique constraint on `name` (allows case variations)
- Application-level deduplication via case-insensitive lookup

**Performance Impact**:
- Asset lookup: Index scan on `name` (case-insensitive query may use LOWER(name) function)
- Duplicate check: Composite index on (asset_id, vulnerability_id, scan_timestamp) → fast lookup
- User lookup: Primary key or username index (existing)

## Data Mapping

### CrowdStrike DTO → Asset Entity

| CrowdStrike Field | Asset Field | Transformation | Default |
|-------------------|-------------|----------------|---------|
| `hostname` | `name` | Direct | Required |
| `ip` | `ip` | Direct | null if not provided |
| N/A | `type` | Constant | "Server" |
| N/A | `owner` | From authentication | `authentication.name` |
| N/A | `manualCreator` | User lookup | `userRepository.findByUsername(authentication.name)` |
| N/A | `workgroups` | Empty set | `mutableSetOf()` |
| N/A | `description` | Null | null |
| N/A | `lastSeen` | Current time | `LocalDateTime.now()` |

### CrowdStrike DTO → Vulnerability Entity

| CrowdStrike Field | Vulnerability Field | Transformation | Validation |
|-------------------|---------------------|----------------|------------|
| `cveId` | `vulnerabilityId` | Direct | CVE-YYYY-NNNNN+ format |
| `severity` | `cvssSeverity` | Direct | Must be in valid set |
| `affectedProduct` | `vulnerableProductVersions` | Direct (truncate if >1000 chars) | None |
| `daysOpen` | `daysOpen` | Parse integer | Must be numeric if present |
| `detectedAt` | `scanTimestamp` | Direct | Valid LocalDateTime |
| Created/found asset | `asset` | Reference | Must exist |

## Summary

**Schema Changes**: ✅ None required (all fields exist)

**Behavioral Changes**:
- Asset creation: Proper user attribution, type assignment, audit trail
- Asset updates: IP address updates for existing assets
- Vulnerability validation: Skip invalid, continue with valid
- Duplicate detection: Prevent exact duplicates, maintain scan history
- Case-insensitive matching: Prevent duplicate assets with different casing

**Data Integrity**:
- Transactions ensure atomicity (all-or-nothing for asset + vulnerabilities)
- Unique constraints prevent exact duplicate vulnerabilities
- Foreign keys enforce referential integrity
- Validation prevents malformed data

**Performance**:
- Existing indexes support all queries
- Case-insensitive lookup: Acceptable overhead for deduplication benefit
- Batch size: Up to 100 vulnerabilities per transaction (per SC-001 target)
- Transaction duration: <5 seconds target (achievable with current schema)
