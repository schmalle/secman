# Data Model: Vulnerability Management

**Feature**: Vulnerability Management System
**Date**: 2025-10-03

## Entity Overview
This feature introduces one new entity (Vulnerability) and extends an existing entity (Asset) to support vulnerability tracking and management.

---

## Entities

### 1. Vulnerability (NEW)
Represents a security vulnerability discovered during a scan, linked to a specific asset.

#### Fields
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | PK, Auto-generated | Unique identifier |
| asset | Asset | FK (ManyToOne), Non-null | Asset this vulnerability affects |
| vulnerabilityId | String | Nullable, Max 255 | CVE/vulnerability identifier (e.g., "CVE-2016-2183") |
| cvssSeverity | String | Nullable, Max 50 | CVSS severity level (e.g., "High", "Critical", "Medium") |
| vulnerableProductVersions | String | Nullable, Max 512 | Affected product/version info |
| daysOpen | String | Nullable, Max 50 | Text representation of days open (e.g., "58 days") |
| scanTimestamp | LocalDateTime | Non-null | When the scan was performed (user-specified) |
| createdAt | LocalDateTime | Auto-generated | When record was imported into system |

#### Annotations (Kotlin/JPA)
```kotlin
@Entity
@Table(
    name = "vulnerability",
    indexes = [
        Index(name = "idx_vulnerability_asset", columnList = "asset_id"),
        Index(name = "idx_vulnerability_asset_scan", columnList = "asset_id,scan_timestamp")
    ]
)
data class Vulnerability(
    @Id @GeneratedValue var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "asset_id", nullable = false) var asset: Asset,
    @Column(name = "vulnerability_id") var vulnerabilityId: String? = null,
    @Column(name = "cvss_severity", length = 50) var cvssSeverity: String? = null,
    @Column(name = "vulnerable_product_versions", length = 512) var vulnerableProductVersions: String? = null,
    @Column(name = "days_open", length = 50) var daysOpen: String? = null,
    @Column(name = "scan_timestamp", nullable = false) var scanTimestamp: LocalDateTime,
    @Column(name = "created_at", updatable = false) var createdAt: LocalDateTime? = null
) {
    @PrePersist
    fun onCreate() {
        createdAt = LocalDateTime.now()
    }
}
```

#### Validation Rules
- scanTimestamp: Required (non-null), must be reasonable date (not future, not >10 years past)
- vulnerabilityId: Optional, format validation recommended (CVE-\d{4}-\d{4,7})
- cvssSeverity: Optional, enum validation recommended (Critical, High, Medium, Low, Informational)
- daysOpen: Optional, text field (parsed from Excel "58 days" format)
- Excel empty cells → null values (preserve empty state)

#### Relationships
- **ManyToOne** with Asset (vulnerability.asset_id → asset.id)
  - Fetch: LAZY (avoid N+1 queries)
  - Cascade: None (vulnerabilities don't cascade operations to assets)
  - Orphan removal: Yes (delete vulnerability if asset deleted)

---

### 2. Asset (EXTENDED)
Existing entity representing IT assets, extended to support cloud metadata, groups, and vulnerability tracking.

#### New Fields
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| groups | String | Nullable, Max 512 | Comma-separated group names (e.g., "SVR-MS-DMZ,Production") |
| cloudAccountId | String | Nullable, Max 255 | Cloud service account ID |
| cloudInstanceId | String | Nullable, Max 255 | Cloud service instance ID |
| adDomain | String | Nullable, Max 255 | Active Directory domain (e.g., "MS.HOME") |
| osVersion | String | Nullable, Max 255 | Operating system version (e.g., "Windows Server 2030") |
| vulnerabilities | List\<Vulnerability\> | OneToMany, Lazy | Vulnerabilities affecting this asset |

#### Updated Annotations (Add to existing Asset.kt)
```kotlin
@Column(name = "groups", length = 512)
var groups: String? = null

@Column(name = "cloud_account_id")
var cloudAccountId: String? = null

@Column(name = "cloud_instance_id")
var cloudInstanceId: String? = null

@Column(name = "ad_domain")
var adDomain: String? = null

@Column(name = "os_version")
var osVersion: String? = null

@OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
@JsonIgnore
var vulnerabilities: MutableList<Vulnerability> = mutableListOf()
```

#### Excel Column Mapping
| Excel Column | Asset Field | Notes |
|--------------|-------------|-------|
| Hostname | name | Existing field, used for asset lookup |
| Local IP | ip | Existing field, may be updated on conflict |
| Host groups | groups | New field, merge on conflict (append) |
| Cloud service account ID | cloudAccountId | New field |
| Cloud service instance ID | cloudInstanceId | New field |
| OS version | osVersion | New field |
| Active Directory domain | adDomain | New field |

#### Validation Rules (New Fields Only)
- groups: Optional, stored as comma-separated string, trimmed
- All cloud/AD/OS fields: Optional, max 255 characters
- Hostname (name): Required (existing constraint), used for findByHostname lookup

#### Default Values (Auto-Created Assets)
When creating new asset from vulnerability data:
- owner: "Security Team"
- type: "Server"
- description: "Auto-created from vulnerability scan"
- name: From Excel "Hostname" column
- ip: From Excel "Local IP" column (if present)
- groups: From Excel "Host groups" column (if present)
- Other fields: From Excel columns (if present)

---

## Relationships

### Vulnerability ↔ Asset
- **Cardinality**: Many Vulnerabilities to One Asset
- **Direction**: Bidirectional
- **Foreign Key**: vulnerability.asset_id → asset.id
- **Cascade**:
  - Delete Asset → Delete all Vulnerabilities (orphanRemoval = true)
  - Delete Vulnerability → No effect on Asset
- **Fetch Strategy**: LAZY on both sides
- **JSON Serialization**: @JsonIgnore on Asset.vulnerabilities (prevent lazy load errors)

### Diagram
```
┌─────────────────────┐       ┌─────────────────────┐
│   Asset             │       │   Vulnerability     │
├─────────────────────┤       ├─────────────────────┤
│ id (PK)             │◄──────┤ id (PK)             │
│ name                │       │ asset_id (FK)       │
│ ip                  │  1    │ vulnerability_id    │
│ owner               │       │ cvss_severity       │
│ type                │       │ scan_timestamp      │
│ groups (NEW)        │   *   │ created_at          │
│ cloudAccountId (NEW)│       │ ...                 │
│ vulnerabilities     │       └─────────────────────┘
└─────────────────────┘
```

---

## Indexes

### Vulnerability Table
1. **idx_vulnerability_asset**: ON asset_id
   - Purpose: Fast joins with Asset table
   - Queries: SELECT * FROM vulnerability WHERE asset_id = ?

2. **idx_vulnerability_asset_scan**: ON (asset_id, scan_timestamp)
   - Purpose: Historical queries (vulnerabilities for asset over time)
   - Queries: SELECT * FROM vulnerability WHERE asset_id = ? ORDER BY scan_timestamp DESC

### Asset Table (Enhancement)
3. **idx_asset_name**: ON name
   - Purpose: Fast hostname lookups during import
   - Queries: SELECT * FROM asset WHERE name = ?

---

## State Transitions
N/A - These entities are CRUD-only, no workflow states.

Vulnerability lifecycle:
1. Created during Excel import (scanTimestamp = user-specified date)
2. Read/displayed in asset inventory
3. Deleted when asset deleted (cascade)

Asset merge lifecycle:
1. Lookup by hostname (name field)
2. If exists → Merge (append groups, update IP, preserve others)
3. If not exists → Create with defaults

---

## Query Patterns

### 1. Import Flow Queries
```kotlin
// Find existing asset by hostname
assetRepository.findByName(hostname)

// Create new vulnerability
vulnerability.asset = asset
vulnerabilityRepository.save(vulnerability)

// Merge asset groups
val existingGroups = asset.groups?.split(",")?.map { it.trim() } ?: emptyList()
val newGroups = importedGroups.split(",").map { it.trim() }
val mergedGroups = (existingGroups + newGroups).distinct().joinToString(", ")
asset.groups = mergedGroups
```

### 2. Display Vulnerabilities for Asset
```kotlin
// Option 1: Load via relationship
val asset = assetRepository.findById(assetId)
val vulnerabilities = asset.vulnerabilities // Lazy loaded

// Option 2: Query directly (better for pagination)
val vulnerabilities = vulnerabilityRepository.findByAssetId(assetId, pageable)
```

### 3. Historical Vulnerability Queries
```kotlin
// Get vulnerabilities for asset scanned in date range
vulnerabilityRepository.findByAssetIdAndScanTimestampBetween(
    assetId,
    startDate,
    endDate,
    Sort.by("scanTimestamp").descending()
)
```

---

## Data Migration
Hibernate auto-creates tables and columns:
- Vulnerability table created on first deployment
- Asset table altered to add new columns (groups, cloudAccountId, etc.)
- Indexes created automatically via @Table(indexes = [...])

No manual migration scripts required (Hibernate handles schema evolution).

**Rollback Safety**: New columns are nullable, backward compatible. Old code ignores new fields.

---

## Constraints Summary
| Constraint | Entity | Enforcement |
|------------|--------|-------------|
| asset_id NOT NULL | Vulnerability | Database + JPA |
| scan_timestamp NOT NULL | Vulnerability | Database + JPA |
| name NOT NULL | Asset | Database + JPA (existing) |
| ON DELETE CASCADE | Vulnerability → Asset | Database FK + JPA orphanRemoval |
| Max lengths | All string fields | Database + JPA @Column |
| Unique asset by hostname | Asset | Application logic (findByName) |

---

## Testing Considerations
1. **Unit Tests**:
   - Vulnerability entity creation, validation
   - Asset merge logic (group append, IP update)
   - Null/empty field handling

2. **Integration Tests**:
   - Create vulnerability with asset relationship
   - Cascade delete (delete asset → vulnerabilities deleted)
   - Query performance (index usage, lazy loading)

3. **Test Data**:
   - Assets with/without vulnerabilities
   - Vulnerabilities with null fields
   - Historical vulnerability scans (multiple scan dates)
   - Assets with complex group strings ("Group1,Group2,Group3")
