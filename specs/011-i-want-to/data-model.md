# Data Model: Release-Based Requirement Version Management

**Feature**: 011-i-want-to
**Date**: 2025-10-05
**Status**: Design Complete

## Entity Overview

This feature introduces **1 new entity** (RequirementSnapshot) and **1 role extension** (RELEASE_MANAGER). It leverages existing Release and VersionedEntity infrastructure.

## Entity: RequirementSnapshot

**Purpose**: Immutable snapshot of a Requirement at the time a Release was created

**Lifecycle**: Created when Release is created, deleted when Release is deleted (cascade)

### Fields

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | PK, Auto-generated | Unique snapshot identifier |
| release | Release (FK) | NOT NULL, ON DELETE CASCADE | Parent release containing this snapshot |
| originalRequirementId | Long | NOT NULL, Indexed | Original requirement ID for traceability |
| shortreq | String | NOT NULL | Frozen: Short requirement description |
| details | Text | NULL | Frozen: Detailed requirement description |
| language | String | NULL | Frozen: Language code (e.g., "en", "de") |
| example | Text | NULL | Frozen: Example implementation |
| motivation | Text | NULL | Frozen: Rationale for requirement |
| usecase | Text | NULL | Frozen: Use case description (legacy field) |
| norm | Text | NULL | Frozen: Norm reference (legacy field) |
| chapter | Text | NULL | Frozen: Chapter/section reference |
| usecaseIdsSnapshot | Text (JSON) | NULL | Frozen: Array of UseCase IDs at freeze time |
| normIdsSnapshot | Text (JSON) | NULL | Frozen: Array of Norm IDs at freeze time |
| snapshotTimestamp | Instant | NOT NULL, Default: now() | When snapshot was created |

### Indexes

```sql
CREATE INDEX idx_snapshot_release ON requirement_snapshot(release_id);
CREATE INDEX idx_snapshot_original ON requirement_snapshot(original_requirement_id);
```

### Relationships

- **ManyToOne → Release**: Each snapshot belongs to exactly one release
  - Cascade: DELETE (when release deleted, snapshots deleted)
  - Fetch: LAZY (don't load all snapshots when loading release metadata)

- **Logical → Requirement**: `originalRequirementId` references Requirement.id
  - NOT a foreign key (requirement may be deleted after snapshot)
  - Used for traceability and comparison queries

### Validation Rules

- `shortreq` must not be blank (inherited from Requirement validation)
- All frozen fields copied directly from Requirement at freeze time
- Snapshot is immutable after creation (no update operations)
- `usecaseIdsSnapshot` and `normIdsSnapshot` stored as JSON arrays (e.g., "[1,2,3]")

### JPA Entity Definition

```kotlin
package com.secman.domain

import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Entity
@Table(
    name = "requirement_snapshot",
    indexes = [
        Index(name = "idx_snapshot_release", columnList = "release_id"),
        Index(name = "idx_snapshot_original", columnList = "original_requirement_id")
    ]
)
@Serdeable
data class RequirementSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_id", nullable = false)
    var release: Release,

    @Column(name = "original_requirement_id", nullable = false)
    var originalRequirementId: Long,

    @Column(nullable = false)
    @NotBlank
    var shortreq: String,

    @Column(name = "description", columnDefinition = "TEXT")
    var details: String? = null,

    @Column
    var language: String? = null,

    @Column(columnDefinition = "TEXT")
    var example: String? = null,

    @Column(columnDefinition = "TEXT")
    var motivation: String? = null,

    @Column(columnDefinition = "TEXT")
    var usecase: String? = null,

    @Column(name = "norm", columnDefinition = "TEXT")
    var norm: String? = null,

    @Column(name = "chapter", columnDefinition = "TEXT")
    var chapter: String? = null,

    @Column(columnDefinition = "TEXT")
    var usecaseIdsSnapshot: String? = null,  // JSON: [1,2,3]

    @Column(columnDefinition = "TEXT")
    var normIdsSnapshot: String? = null,  // JSON: [1,2,3]

    @Column(name = "snapshot_timestamp", nullable = false, updatable = false)
    var snapshotTimestamp: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RequirementSnapshot) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String =
        "RequirementSnapshot(id=$id, release=${release.id}, originalRequirementId=$originalRequirementId, shortreq='${shortreq.take(50)}...')"

    companion object {
        fun fromRequirement(requirement: Requirement, release: Release): RequirementSnapshot {
            return RequirementSnapshot(
                release = release,
                originalRequirementId = requirement.id!!,
                shortreq = requirement.shortreq,
                details = requirement.details,
                language = requirement.language,
                example = requirement.example,
                motivation = requirement.motivation,
                usecase = requirement.usecase,
                norm = requirement.norm,
                chapter = requirement.chapter,
                usecaseIdsSnapshot = requirement.usecases.mapNotNull { it.id }.joinToString(",", "[", "]"),
                normIdsSnapshot = requirement.norms.mapNotNull { it.id }.joinToString(",", "[", "]"),
                snapshotTimestamp = Instant.now()
            )
        }
    }
}
```

## Entity: Release (Existing - No Changes)

**Purpose**: Represents a point-in-time version of the requirement set

**Existing Fields** (no modifications needed):
- id, version (unique), name, description
- status (DRAFT/PUBLISHED/ARCHIVED)
- releaseDate, createdBy, createdAt, updatedAt

**Existing Validation**:
- version: unique, matches semantic versioning regex
- name: NOT NULL, max 100 chars

**Note**: Release entity already exists in codebase. No schema changes required.

## Entity: Requirement (Existing - No Changes)

**Purpose**: The current, editable requirement entity

**Existing Fields**:
- id, shortreq, details, language, example, motivation, usecase, norm, chapter
- usecases (ManyToMany UseCase), norms (ManyToMany Norm)
- Extends VersionedEntity (release, versionNumber, isCurrent)

**Behavior Changes**:
- Deletion prevention: Cannot delete if frozen in any release (checked in service layer)
- Update behavior: Updates only affect current requirement, not snapshots

**Note**: Requirement entity already exists. No schema changes needed.

## Role Extension: RELEASE_MANAGER

**Purpose**: Permission to create/delete releases without full ADMIN privileges

**Implementation**: Add to User.roles enum

```kotlin
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "user_roles", joinColumns = [JoinColumn(name = "user_id")])
@Column(name = "role")
var roles: MutableSet<String> = mutableSetOf()

// Valid values: "USER", "ADMIN", "VULN", "RELEASE_MANAGER"
```

**Authorization Rules**:
- Release creation: ADMIN || RELEASE_MANAGER
- Release deletion: ADMIN || RELEASE_MANAGER
- Release viewing: Any authenticated user
- Export from release: Any authenticated user
- Compare releases: Any authenticated user

## Repository: RequirementSnapshotRepository

**Purpose**: Data access for requirement snapshots

```kotlin
package com.secman.repository

import com.secman.domain.RequirementSnapshot
import com.secman.domain.Release
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

@Repository
interface RequirementSnapshotRepository : JpaRepository<RequirementSnapshot, Long> {

    fun findByRelease(release: Release): List<RequirementSnapshot>

    fun findByReleaseId(releaseId: Long): List<RequirementSnapshot>

    fun findByOriginalRequirementId(requirementId: Long): List<RequirementSnapshot>

    fun countByReleaseId(releaseId: Long): Long

    fun deleteByReleaseId(releaseId: Long)
}
```

## Database Schema DDL

**Auto-generated by Hibernate on first deployment**:

```sql
CREATE TABLE requirement_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    release_id BIGINT NOT NULL,
    original_requirement_id BIGINT NOT NULL,
    shortreq VARCHAR(255) NOT NULL,
    description TEXT,
    language VARCHAR(255),
    example TEXT,
    motivation TEXT,
    usecase TEXT,
    norm TEXT,
    chapter TEXT,
    usecase_ids_snapshot TEXT,
    norm_ids_snapshot TEXT,
    snapshot_timestamp DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_snapshot_release FOREIGN KEY (release_id) REFERENCES releases(id) ON DELETE CASCADE,
    INDEX idx_snapshot_release (release_id),
    INDEX idx_snapshot_original (original_requirement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## Data Flow Diagrams

### Release Creation Flow
```
1. User (ADMIN/RELEASE_MANAGER) → POST /api/releases {version, name, description}
2. ReleaseController → validate semantic version format
3. ReleaseController → check uniqueness
4. ReleaseService.createRelease()
   a. Create Release entity, status=DRAFT
   b. Save Release to get ID
   c. Query all current Requirements (isCurrent=true)
   d. For each Requirement:
      - Create RequirementSnapshot.fromRequirement(req, release)
      - Set release FK, copy all fields, serialize relationships to JSON
   e. Bulk insert snapshots
   f. Return Release with snapshot count
5. Response: 201 Created {id, version, requirementCount}
```

### Export with Release Flow
```
1. User → GET /api/requirements/export/xlsx?releaseId=123
2. RequirementController.exportToExcel(releaseId)
3. If releaseId present:
   a. ReleaseRepository.findById(releaseId)
   b. RequirementSnapshotRepository.findByReleaseId(releaseId)
   c. Map snapshots → RequirementDTO for export
   d. Generate filename: "requirements_v{release.version}_{date}.xlsx"
   e. Add release metadata to export header
4. If releaseId absent:
   a. RequirementRepository.findAll(isCurrent=true)
   b. Generate filename: "requirements_current_{date}.xlsx"
5. Create Excel file via Apache POI
6. Response: 200 OK with file stream
```

### Comparison Flow
```
1. User → GET /api/releases/compare?fromReleaseId=1&toReleaseId=2
2. ReleaseComparisonController.compare(fromId, toId)
3. Load both releases:
   a. Release from = releaseRepository.findById(fromId)
   b. Release to = releaseRepository.findById(toId)
4. Load snapshots:
   a. List<Snapshot> fromSnapshots = snapshotRepository.findByReleaseId(fromId)
   b. List<Snapshot> toSnapshots = snapshotRepository.findByReleaseId(toId)
5. RequirementComparisonService.compare(fromSnapshots, toSnapshots):
   a. Build Map<originalRequirementId, Snapshot> for each list
   b. Iterate fromSnapshots → find deleted (not in toMap)
   c. Iterate toSnapshots → find added (not in fromMap), modified (different fields)
   d. For modified: compare each field, record FieldChange objects
   e. Count unchanged
6. Response: ComparisonResult {fromRelease, toRelease, added[], deleted[], modified[], unchanged}
```

## Deletion Prevention Logic

**Requirement Deletion Check**:
```kotlin
fun deleteRequirement(id: Long): Result {
    val releases = snapshotRepository.findByOriginalRequirementId(id)
    if (releases.isNotEmpty()) {
        val releaseVersions = releases.map { it.release.version }.distinct()
        return Result.error("Cannot delete requirement: frozen in releases ${releaseVersions.joinToString(", ")}")
    }
    requirementRepository.deleteById(id)
    return Result.success()
}
```

## State Diagram: Release Lifecycle

```
[DRAFT] --publish()--> [PUBLISHED] --archive()--> [ARCHIVED]
   |                                                      |
   +-------------- delete() (ADMIN/RELEASE_MANAGER) -----+
                           |
                    CASCADE DELETE snapshots
```

**State Transitions**:
- CREATE: Any user with ADMIN || RELEASE_MANAGER → DRAFT
- PUBLISH: ADMIN || RELEASE_MANAGER → PUBLISHED
- ARCHIVE: ADMIN || RELEASE_MANAGER → ARCHIVED
- DELETE: ADMIN || RELEASE_MANAGER (cascade deletes snapshots)

**Immutability**:
- Release metadata (version, name, description) immutable after creation
- Snapshots immutable after creation
- Only status can transition (DRAFT → PUBLISHED → ARCHIVED)

## Query Patterns

**Frequent Queries**:
1. Get all snapshots for a release: `findByReleaseId(id)` - indexed
2. Export from release: `findByReleaseId(id)` + format conversion
3. Compare releases: Two `findByReleaseId()` calls + in-memory diff
4. Check if requirement can be deleted: `findByOriginalRequirementId(id)`
5. Count requirements in release: `countByReleaseId(id)`

**Performance Expectations**:
- Query 1-5: <50ms with indexes
- Snapshot creation (1000 requirements): <1 second (bulk insert)
- Comparison (1000 vs 1000): <500ms (in-memory diff)

## Migration Strategy

**Initial Deployment**:
1. Hibernate auto-creates `requirement_snapshot` table
2. No data migration needed (no existing snapshots)
3. Add RELEASE_MANAGER to User.roles enum (code-level change)

**Future Compatibility**:
- Table is additive - doesn't modify existing schema
- Foreign key to Release (existing table)
- No breaking changes to Requirement or Release entities

---
*Data model design complete. Ready for contract generation.*
