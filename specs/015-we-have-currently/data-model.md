# Data Model: CrowdStrike System Vulnerability Lookup

**Date**: 2025-10-11
**Feature**: CrowdStrike System Vulnerability Lookup
**Branch**: 015-we-have-currently

## Overview

This feature introduces new DTOs for CrowdStrike API integration while reusing existing domain entities (Vulnerability, Asset) for data persistence. No schema changes are required.

## New Data Transfer Objects (DTOs)

### CrowdStrikeVulnerabilityDto

**Purpose**: Transport vulnerability data from CrowdStrike API to frontend

```kotlin
data class CrowdStrikeVulnerabilityDto(
    val id: String,                          // Unique ID from CrowdStrike
    val hostname: String,                    // System hostname
    val ip: String?,                         // System IP address
    val cveId: String?,                      // CVE identifier (e.g., "CVE-2021-44228")
    val severity: String,                    // CVSS severity: Critical/High/Medium/Low
    val cvssScore: Double?,                  // Numeric CVSS score (0.0-10.0)
    val affectedProduct: String?,            // Vulnerable product/version
    val daysOpen: String?,                   // Days since detection (e.g., "15 days")
    val detectedAt: LocalDateTime,           // Detection timestamp
    val status: String,                      // Status: "open", "closed", etc.
    val hasException: Boolean = false,       // Matched against VulnerabilityException
    val exceptionReason: String? = null      // Exception reason if hasException=true
)
```

**Validation Rules**:
- `hostname`: Required, non-blank, max 255 chars
- `severity`: Must be one of: "Critical", "High", "Medium", "Low", "Informational"
- `cvssScore`: Must be 0.0-10.0 if present
- `detectedAt`: Cannot be in the future

**Sources**:
- Populated from CrowdStrike Spotlight API response
- `hasException` computed by checking VulnerabilityException table
- `daysOpen` calculated from `detectedAt` to current date

### CrowdStrikeQueryRequest

**Purpose**: Request body for querying CrowdStrike API

```kotlin
data class CrowdStrikeQueryRequest(
    @NotBlank
    @Size(min = 1, max = 255)
    val hostname: String
)
```

**Validation**:
- Required field
- Trimmed before processing
- Validated against allowed hostname patterns (alphanumeric, dots, hyphens)

### CrowdStrikeQueryResponse

**Purpose**: Response from query endpoint

```kotlin
data class CrowdStrikeQueryResponse(
    val hostname: String,
    val vulnerabilities: List<CrowdStrikeVulnerabilityDto>,
    val totalCount: Int,
    val queriedAt: LocalDateTime
)
```

**Fields**:
- `hostname`: Echoed from request for confirmation
- `vulnerabilities`: List of found vulnerabilities (empty if none)
- `totalCount`: Total count from CrowdStrike (may be > list size if limited to 1000)
- `queriedAt`: Timestamp of query execution

### CrowdStrikeSaveRequest

**Purpose**: Request body for saving vulnerabilities to database

```kotlin
data class CrowdStrikeSaveRequest(
    @NotBlank
    val hostname: String,

    @NotEmpty
    val vulnerabilities: List<CrowdStrikeVulnerabilityDto>
)
```

**Validation**:
- `hostname`: Required, non-blank
- `vulnerabilities`: At least one item required

### CrowdStrikeSaveResponse

**Purpose**: Response from save endpoint

```kotlin
data class CrowdStrikeSaveResponse(
    val message: String,
    val vulnerabilitiesSaved: Int,
    val assetsCreated: Int,
    val errors: List<String> = emptyList()
)
```

**Fields**:
- `message`: Human-readable summary (e.g., "Saved 15 vulnerabilities for system 'web-server-01'")
- `vulnerabilitiesSaved`: Count of Vulnerability records created
- `assetsCreated`: Count of new Asset records created (0 if asset existed)
- `errors`: List of errors encountered (partial save allowed)

## Existing Domain Entities (Reused)

### Vulnerability

**Entity**: `com.secman.domain.Vulnerability` (Existing - no changes)

```kotlin
@Entity
@Table(name = "vulnerability")
data class Vulnerability(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    var asset: Asset,

    @Column(name = "vulnerability_id", length = 255)
    var vulnerabilityId: String? = null,          // CVE ID

    @Column(name = "cvss_severity", length = 50)
    var cvssSeverity: String? = null,             // Critical/High/Medium/Low

    @Column(name = "vulnerable_product_versions", length = 512)
    var vulnerableProductVersions: String? = null,

    @Column(name = "days_open", length = 50)
    var daysOpen: String? = null,

    @Column(name = "scan_timestamp", nullable = false)
    var scanTimestamp: LocalDateTime,

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null
)
```

**Usage in CrowdStrike Feature**:
- Created when saving CrowdStrike vulnerabilities to database
- `asset`: Linked to existing or newly created Asset
- `vulnerabilityId`: Populated from `cveId`
- `cvssSeverity`: Populated from `severity`
- `vulnerableProductVersions`: Populated from `affectedProduct`
- `daysOpen`: Populated from calculated days
- `scanTimestamp`: Populated from `detectedAt`

**Indexes** (existing):
- `idx_vulnerability_asset`: asset_id
- `idx_vulnerability_asset_scan`: asset_id, scan_timestamp
- `idx_vulnerability_severity`: cvss_severity

### Asset

**Entity**: `com.secman.domain.Asset` (Existing - no changes)

```kotlin
@Entity
@Table(name = "asset")
data class Asset(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "name", length = 255)
    var name: String,                             // Hostname

    @Column(name = "ip", length = 50)
    var ip: String? = null,

    @Column(name = "type", length = 100)
    var type: String? = null,                     // Default: "Endpoint"

    @Column(name = "owner", length = 255)
    var owner: String? = null,                    // Default: "CrowdStrike"

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String? = null,

    @Column(name = "last_seen")
    var lastSeen: LocalDateTime? = null,

    @OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true)
    var vulnerabilities: MutableList<Vulnerability> = mutableListOf()
)
```

**Usage in CrowdStrike Feature**:
- Matched by hostname (case-insensitive) or IP address
- Created if not found with defaults:
  - `name`: hostname from CrowdStrike
  - `ip`: IP from CrowdStrike
  - `type`: "Endpoint"
  - `owner`: "CrowdStrike"
  - `description`: ""
  - `lastSeen`: current timestamp

**Matching Algorithm**:
1. Search by `name` (case-insensitive): `assetRepository.findByNameIgnoreCase(hostname)`
2. If not found, search by `ip`: `assetRepository.findByIp(ip)`
3. If still not found, create new Asset with defaults

### VulnerabilityException

**Entity**: `com.secman.domain.VulnerabilityException` (Existing - used for checking exceptions)

```kotlin
@Entity
@Table(name = "vulnerability_exception")
data class VulnerabilityException(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "exception_type", length = 50)
    var exceptionType: String,                    // "IP" or "PRODUCT"

    @Column(name = "target_value", length = 255)
    var targetValue: String,

    @Column(name = "expiration_date")
    var expirationDate: LocalDate? = null,

    @Column(name = "reason", columnDefinition = "TEXT")
    var reason: String? = null,

    @Column(name = "created_by", length = 255)
    var createdBy: String,

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime? = null,

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
) {
    fun isActive(): Boolean {
        return expirationDate == null || expirationDate!!.isAfter(LocalDate.now())
    }

    fun matches(vulnerability: CrowdStrikeVulnerabilityDto, asset: Asset): Boolean {
        if (!isActive()) return false

        return when (exceptionType) {
            "IP" -> targetValue.equals(asset.ip, ignoreCase = true)
            "PRODUCT" -> vulnerability.affectedProduct?.contains(targetValue, ignoreCase = true) == true
            else -> false
        }
    }
}
```

**Usage in CrowdStrike Feature**:
- Query all active exceptions when returning results
- Check each vulnerability against exceptions
- Populate `hasException` and `exceptionReason` in DTOs
- Used for display only (not persisted with vulnerability until saved)

## Data Flow

### Query Flow

```
User Input (hostname)
    ↓
CrowdStrikeController.queryVulnerabilities()
    ↓
CrowdStrikeVulnerabilityService.queryByHostname(hostname)
    ↓
1. Authenticate with CrowdStrike (OAuth2)
2. Query Spotlight API: GET /spotlight/combined/vulnerabilities/v2
   - Filter: hostname:'<name>'+status:'open'+created_timestamp:>=<40_days_ago>
    ↓
3. Map API response → List<CrowdStrikeVulnerabilityDto>
   - Convert CVSS score to severity text
   - Calculate days open
   - Match against VulnerabilityException (hasException)
    ↓
4. Return CrowdStrikeQueryResponse
    ↓
Frontend displays in table
```

### Save Flow

```
User clicks "Save to Database"
    ↓
CrowdStrikeController.saveVulnerabilities(CrowdStrikeSaveRequest)
    ↓
CrowdStrikeVulnerabilityService.saveToDatabase(hostname, vulnerabilities)
    ↓
For each vulnerability:
    1. Find or create Asset (by hostname or IP)
    2. Create Vulnerability entity:
       - Link to Asset
       - Map DTO fields to entity fields
       - Set scanTimestamp = detectedAt
    3. Save Vulnerability
    ↓
Return CrowdStrikeSaveResponse
    ↓
Frontend shows success message
```

## Entity Relationships

```
CrowdStrike API (external)
    ↓ (query)
CrowdStrikeVulnerabilityDto (transient DTO)
    ↓ (save operation)
Vulnerability (persistent entity)
    ↓ (ManyToOne)
Asset (persistent entity)

VulnerabilityException (persistent entity)
    ↓ (checked against)
CrowdStrikeVulnerabilityDto (hasException flag)
```

**Key Points**:
- DTOs are transient (not persisted)
- Vulnerability and Asset are persistent
- VulnerabilityException is checked for display only
- No new tables or schema changes required

## Exception Handling in Data Layer

### Duplicate Prevention

**Strategy**: Allow duplicates differentiated by scanTimestamp

- CrowdStrike vulnerabilities are saved with `scanTimestamp = detectedAt`
- Same CVE on same asset at different times = separate records
- This preserves historical data (per FR-012)

**Example**:
```
Asset: web-server-01
CVE-2021-44228, scanTimestamp: 2025-09-15  (imported earlier)
CVE-2021-44228, scanTimestamp: 2025-10-11  (from CrowdStrike today)
→ Both records kept, differentiated by timestamp
```

### Asset Creation Edge Cases

**Case 1**: Hostname exists but different IP
- Action: Use existing Asset, update IP to CrowdStrike value
- Rationale: Hostname is primary identifier, IP may change (DHCP)

**Case 2**: IP exists but different hostname
- Action: Create new Asset with CrowdStrike hostname
- Rationale: Same IP, different hostname = different system (or hostname change)

**Case 3**: Both hostname and IP exist (same asset)
- Action: Use existing Asset, no updates
- Rationale: Asset already correctly identified

**Case 4**: Neither hostname nor IP exists
- Action: Create new Asset with defaults
- Rationale: New system discovered by CrowdStrike

## Validation Summary

| Field | Validation Rule | Error Message |
|-------|-----------------|---------------|
| hostname (input) | Non-blank, 1-255 chars, valid hostname chars | "Invalid hostname format" |
| cveId | Optional, but if present must match CVE pattern | "Invalid CVE format" |
| severity | Must be Critical/High/Medium/Low/Informational | "Invalid severity value" |
| cvssScore | Must be 0.0-10.0 if present | "Invalid CVSS score" |
| detectedAt | Cannot be in future | "Invalid detection timestamp" |

## Performance Considerations

### Query Performance
- CrowdStrike API call: ~2-5 seconds (network + processing)
- Exception matching: O(n*m) where n=vulnerabilities, m=active exceptions
  - Optimized by loading exceptions once per query
  - Typical: 100 vulnerabilities × 10 exceptions = 1000 comparisons (< 1ms)

### Save Performance
- Asset lookup: Indexed queries (fast)
- Vulnerability creation: Bulk insert not used (individual saves)
- Typical: 100 vulnerabilities × (1 lookup + 1 insert) = ~200 DB operations
- Expected time: < 2 seconds for 100 vulnerabilities

### Memory Footprint
- CrowdStrikeVulnerabilityDto: ~500 bytes each
- 1000 vulnerabilities: ~500 KB
- Safe for in-memory processing (no streaming needed)

## Migration Notes

**No database migrations required** - This feature uses existing schema:
- Vulnerability table (unchanged)
- Asset table (unchanged)
- VulnerabilityException table (unchanged)

All new data structures are DTOs (transient) or service layer components.
