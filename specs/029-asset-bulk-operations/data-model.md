# Data Model: Asset Bulk Operations

**Feature**: 029-asset-bulk-operations
**Date**: 2025-10-19

## Overview

This feature leverages existing entities (Asset, Workgroup, User) without introducing new database tables. All data structures are DTOs for request/response handling or temporary in-memory structures for import/export processing.

---

## Existing Entities (No Changes Required)

### Asset (Existing - No Modifications)

**Table**: `asset`
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

**Relevant Fields** (for export/import):
```kotlin
@Entity
@Table(name = "asset")
data class Asset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(nullable = false)
    var type: String,

    @Column(nullable = true)
    var ip: String? = null,

    @Column(nullable = false)
    var owner: String,

    @Column(nullable = true, columnDefinition = "TEXT")
    var description: String? = null,

    @Column(nullable = true)
    var groups: String? = null,

    @Column(nullable = true)
    var cloudAccountId: String? = null,

    @Column(nullable = true)
    var cloudInstanceId: String? = null,

    @Column(nullable = true)
    var osVersion: String? = null,

    @Column(nullable = true)
    var adDomain: String? = null,

    @Column(nullable = true)
    var createdAt: LocalDateTime? = null,

    @Column(nullable = true)
    var updatedAt: LocalDateTime? = null,

    @Column(nullable = true)
    var lastSeen: LocalDateTime? = null,

    // Relationships
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "asset_workgroups",
        joinColumns = [JoinColumn(name = "asset_id")],
        inverseJoinColumns = [JoinColumn(name = "workgroup_id")]
    )
    var workgroups: MutableSet<Workgroup> = mutableSetOf(),

    @OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true)
    var vulnerabilities: MutableSet<Vulnerability> = mutableSetOf(),

    @OneToMany(mappedBy = "asset", cascade = [CascadeType.ALL], orphanRemoval = true)
    var scanResults: MutableSet<ScanResult> = mutableSetOf(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manual_creator_id")
    var manualCreator: User? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_uploader_id")
    var scanUploader: User? = null
)
```

**Key Constraints**:
- `name`: Unique, not null (primary business key for duplicate detection)
- `type`, `owner`: Required fields (validated on import)
- Cascade delete: Vulnerabilities and ScanResults deleted when Asset deleted
- Many-to-many: `asset_workgroups` join table for workgroup memberships

**No Schema Changes**: This feature uses all existing fields and relationships.

---

### Workgroup (Existing - No Modifications)

**Table**: `workgroup`
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`

**Relevant Fields**:
```kotlin
@Entity
@Table(name = "workgroup")
data class Workgroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    var name: String,

    @Column(nullable = true)
    var description: String? = null
)
```

**Usage in Feature**:
- **Export**: Workgroup names included as comma-separated string in Excel
- **Import**: Workgroup names parsed from Excel, matched to existing workgroups by name (case-insensitive)
- **Access Control**: Non-ADMIN users export only assets from their assigned workgroups

---

### User (Existing - No Modifications)

**Table**: `user`
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

**Usage in Feature**:
- **Creator Tracking**: `Asset.manualCreator` set to importing user
- **Authentication**: `Authentication.name` used to filter assets by workgroup membership
- **ADMIN Check**: `Authentication.roles.contains("ADMIN")` determines full access vs workgroup-filtered

---

## DTOs (New - Backend)

### AssetExportDto

**Purpose**: Flattened representation of Asset for Excel export with workgroup names as strings

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/AssetExportDto.kt`

**Structure**:
```kotlin
package com.secman.dto

import java.time.LocalDateTime

data class AssetExportDto(
    val name: String,
    val type: String,
    val ip: String?,
    val owner: String,
    val description: String?,
    val groups: String?,
    val cloudAccountId: String?,
    val cloudInstanceId: String?,
    val osVersion: String?,
    val adDomain: String?,
    val workgroups: String,  // Comma-separated workgroup names
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val lastSeen: LocalDateTime?
) {
    companion object {
        fun fromAsset(asset: Asset): AssetExportDto {
            return AssetExportDto(
                name = asset.name,
                type = asset.type,
                ip = asset.ip,
                owner = asset.owner,
                description = asset.description,
                groups = asset.groups,
                cloudAccountId = asset.cloudAccountId,
                cloudInstanceId = asset.cloudInstanceId,
                osVersion = asset.osVersion,
                adDomain = asset.adDomain,
                workgroups = asset.workgroups.joinToString(", ") { it.name },
                createdAt = asset.createdAt,
                updatedAt = asset.updatedAt,
                lastSeen = asset.lastSeen
            )
        }
    }
}
```

**Validation**: None (output-only DTO, data already validated in Asset entity)

**Usage**:
- Convert Asset entities to DTOs before Excel serialization
- Workgroup names flattened to single comma-separated string for readability

---

### AssetImportDto

**Purpose**: Temporary representation of parsed Excel row before entity creation

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/AssetImportDto.kt`

**Structure**:
```kotlin
package com.secman.dto

import java.time.LocalDateTime

data class AssetImportDto(
    val name: String,
    val type: String,
    val ip: String? = null,
    val owner: String,
    val description: String? = null,
    val groups: String? = null,
    val cloudAccountId: String? = null,
    val cloudInstanceId: String? = null,
    val osVersion: String? = null,
    val adDomain: String? = null,
    val workgroupNames: String? = null,  // Comma-separated, parsed to Workgroup entities
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val lastSeen: LocalDateTime? = null
) {
    fun toAsset(workgroups: Set<Workgroup> = emptySet()): Asset {
        return Asset(
            name = name.trim(),
            type = type.trim(),
            ip = ip?.trim(),
            owner = owner.trim(),
            description = description?.trim(),
            groups = groups?.trim(),
            cloudAccountId = cloudAccountId?.trim(),
            cloudInstanceId = cloudInstanceId?.trim(),
            osVersion = osVersion?.trim(),
            adDomain = adDomain?.trim(),
            workgroups = workgroups.toMutableSet(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastSeen = lastSeen
        )
    }
}
```

**Validation Rules**:
- `name`: Required, non-blank, trimmed
- `type`: Required, non-blank, trimmed
- `owner`: Required, non-blank, trimmed
- `ip`: Optional, validated for IPv4/IPv6 format if present
- `workgroupNames`: Optional, comma-separated string parsed to workgroup entities

**Usage**:
- Parse Excel row to DTO with validation
- Convert DTO to Asset entity after workgroup name resolution
- Validation errors collected per-row for user feedback

---

### ImportResult (Reuse Existing from Feature 013/016)

**Purpose**: Standardized response for import operations

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/ImportResult.kt`

**Structure**:
```kotlin
package com.secman.dto

data class ImportResult(
    val message: String,
    val imported: Int,
    val skipped: Int,
    val assetsCreated: Int = 0,
    val assetsUpdated: Int = 0,
    val errors: List<String> = emptyList()
)
```

**Usage**:
- Return from `POST /api/import/upload-assets-xlsx`
- `imported`: Count of successfully imported assets
- `skipped`: Count of skipped rows (duplicates, validation errors)
- `errors`: List of error messages (limited to first 20 for performance)

---

### BulkDeleteResult

**Purpose**: Response for bulk delete operation with counts

**Location**: `src/backendng/src/main/kotlin/com/secman/dto/BulkDeleteResult.kt`

**Structure**:
```kotlin
package com.secman.dto

data class BulkDeleteResult(
    val deletedAssets: Int,
    val deletedVulnerabilities: Int,
    val deletedScanResults: Int,
    val message: String
) {
    companion object {
        fun success(assetCount: Int, vulnCount: Int, scanCount: Int): BulkDeleteResult {
            return BulkDeleteResult(
                deletedAssets = assetCount,
                deletedVulnerabilities = vulnCount,
                deletedScanResults = scanCount,
                message = "Successfully deleted $assetCount assets, $vulnCount vulnerabilities, and $scanCount scan results"
            )
        }
    }
}
```

**Validation**: None (output-only DTO)

**Usage**:
- Return from `DELETE /api/assets/bulk`
- Provides transparency about scope of deletion (cascaded entities)

---

## Temporary Data Structures (In-Memory Only)

### AssetStyles (Export Helper)

**Purpose**: Pre-created Excel CellStyle objects for performance

**Location**: Inline in `AssetExportService.kt`

**Structure**:
```kotlin
data class AssetStyles(
    val header: CellStyle,
    val date: CellStyle,
    val text: CellStyle,
    val wrapText: CellStyle
)
```

**Usage**:
- Created once before Excel export loop
- Reused for all cells (prevents 64K style limit exhaustion)
- Significantly improves performance (30x faster than per-cell styles)

---

## Data Flow Diagrams

### Export Flow

```
User (with Authentication)
  │
  └──> GET /api/assets/export
         │
         └──> AssetExportService.exportAssets(authentication)
                │
                ├──> Check ADMIN role
                │      ├─ Yes: assetRepository.findAll()
                │      └─ No: assetRepository.findByWorkgroups() + findByCreator()
                │
                └──> Convert Asset → AssetExportDto
                       │
                       └──> AssetExcelWriter.writeToExcel(dtos)
                              │
                              ├──> Create SXSSFWorkbook
                              ├──> Create styles (AssetStyles)
                              ├──> Write header row
                              ├──> Write data rows (foreach DTO)
                              ├──> Set fixed column widths
                              └──> Return ByteArrayOutputStream
```

### Import Flow

```
User uploads Excel file
  │
  └──> POST /api/import/upload-assets-xlsx
         │
         └──> ImportController.uploadAssetsExcel(file, authentication)
                │
                ├──> Validate file (size, format, content-type)
                │
                └──> AssetImportService.importFromExcel(stream, authentication)
                       │
                       ├──> Open XSSFWorkbook
                       ├──> Validate headers (case-insensitive)
                       │
                       └──> For each row:
                              ├──> Parse to AssetImportDto
                              ├──> Validate required fields
                              ├──> Check duplicate by name
                              │      ├─ Exists: Skip, add to errors
                              │      └─ New: Add to batch list
                              │
                              └──> Resolve workgroup names to entities
                       │
                       ├──> Batch save assets (repository.saveAll)
                       ├──> Set manualCreator to importing user
                       └──> Return ImportResult (imported, skipped, errors)
```

### Bulk Delete Flow

```
ADMIN clicks "Delete All Assets"
  │
  └──> Confirmation modal (checkbox + confirm button)
         │
         └──> DELETE /api/assets/bulk
                │
                └──> AssetService.deleteAllAssets()
                       │
                       ├──> Check semaphore (deletionInProgress)
                       │      └─ Busy: Throw ConcurrentOperationException (409)
                       │
                       ├──> @Transactional (timeout=60s)
                       │      │
                       │      ├──> DELETE FROM asset_workgroups (native SQL)
                       │      ├──> vulnerabilityRepository.deleteAllInBatch()
                       │      ├──> scanResultRepository.deleteAllInBatch()
                       │      ├──> Count assets (for response)
                       │      ├──> assetRepository.deleteAllInBatch()
                       │      └──> entityManager.clear()
                       │
                       ├──> Release semaphore (finally block)
                       └──> Return BulkDeleteResult
```

---

## Validation Rules Summary

| Field | Import Validation | Export Behavior |
|-------|------------------|-----------------|
| name | Required, non-blank, unique | As-is |
| type | Required, non-blank | As-is |
| owner | Required, non-blank | As-is |
| ip | Optional, IPv4/IPv6 format if present | As-is (nullable) |
| description | Optional | As-is, wrap text in Excel |
| groups | Optional | As-is |
| cloudAccountId | Optional | As-is |
| cloudInstanceId | Optional | As-is |
| osVersion | Optional | As-is |
| adDomain | Optional | As-is |
| workgroups | Optional, comma-separated names | Comma-separated names from ManyToMany |
| createdAt | Optional, date format | Formatted as "yyyy-MM-dd HH:mm:ss" |
| updatedAt | Optional, date format | Formatted as "yyyy-MM-dd HH:mm:ss" |
| lastSeen | Optional, date format | Formatted as "yyyy-MM-dd HH:mm:ss" |

---

## Index & Performance Considerations

**Existing Indexes** (no changes):
- `asset.name`: Unique index (duplicate detection)
- `asset.id`: Primary key (cascade deletes)
- `asset_workgroups.(asset_id, workgroup_id)`: Join table indexes

**Query Patterns**:
- Export: `SELECT * FROM asset WHERE id IN (workgroup filter)` - Uses workgroup join table index
- Import duplicate check: `SELECT * FROM asset WHERE name = ?` - Uses name unique index
- Bulk delete: `DELETE FROM asset` - Full table scan (acceptable, deleting all rows)

**No New Indexes Required**: Existing indexes support all query patterns efficiently.

---

## Migration Status

**Database Changes**: None required

**Reason**: Feature uses existing Asset, Workgroup, User entities without schema modifications. All new code is application-layer only (DTOs, services, controllers).

**Hibernate Auto-Migration**: No migration scripts needed (`jpa.default.properties.hibernate.hbm2ddl.auto=update` handles any minor adjustments).

---

## Summary

This feature introduces **zero new database tables** and **zero entity modifications**. All data structures are:

1. **Reused Entities**: Asset, Workgroup, User (no changes)
2. **New DTOs**: AssetExportDto, AssetImportDto, BulkDeleteResult (application layer only)
3. **Reused DTOs**: ImportResult (from Features 013/016)
4. **Temporary Structures**: AssetStyles (in-memory, Excel export helper)

Data model complexity: **Minimal** - leverages existing well-tested entities with established relationships and constraints.
