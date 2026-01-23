# Data Model: Requirement ID.Revision Versioning

**Feature**: 066-requirement-versioning
**Date**: 2026-01-23

## Entity Changes

### 1. Requirement (Modified)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/Requirement.kt`

**Current State**: Extends `VersionedEntity`, has `id`, `shortreq`, `details`, etc.

**Changes**:

| Field | Type | Constraint | Description |
|-------|------|------------|-------------|
| `internalId` | VARCHAR(20) | UNIQUE, NOT NULL | Human-readable ID (e.g., "REQ-001") |

**Notes**:
- `versionNumber` from `VersionedEntity` serves as revision (already exists)
- `internalId` assigned on creation, never changes
- Computed property `idRevision` returns `"$internalId.$versionNumber"`

**Entity Definition (additions)**:
```kotlin
@Column(name = "internal_id", unique = true, nullable = false, length = 20)
var internalId: String = ""

// Computed property (not persisted)
val idRevision: String
    get() = "$internalId.$versionNumber"
```

**Validation Rules**:
- `internalId` must match pattern `REQ-\d{3,}` (e.g., REQ-001, REQ-1234)
- `internalId` is immutable after first save
- `versionNumber` increments on content field changes only

---

### 2. RequirementSnapshot (Modified)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/RequirementSnapshot.kt`

**Current State**: Stores requirement data at release time via `originalRequirementId`

**Changes**:

| Field | Type | Constraint | Description |
|-------|------|------------|-------------|
| `internalId` | VARCHAR(20) | NOT NULL | Captured from requirement at snapshot time |
| `revision` | INT | NOT NULL, DEFAULT 1 | Captured revision number |

**Notes**:
- These fields capture the ID.Revision at the moment of release creation
- `originalRequirementId` remains for database-level linking
- Enables accurate diff comparisons showing revision changes

**Entity Definition (additions)**:
```kotlin
@Column(name = "internal_id", nullable = false, length = 20)
var internalId: String = ""

@Column(name = "revision", nullable = false)
var revision: Int = 1

// Computed property
val idRevision: String
    get() = "$internalId.$revision"
```

**Update `fromRequirement()` companion method**:
```kotlin
fun fromRequirement(requirement: Requirement, release: Release): RequirementSnapshot {
    return RequirementSnapshot(
        // ... existing fields ...
        internalId = requirement.internalId,
        revision = requirement.versionNumber,
        // ... rest unchanged ...
    )
}
```

---

### 3. RequirementIdSequence (New)

**File**: `src/backendng/src/main/kotlin/com/secman/domain/RequirementIdSequence.kt`

**Purpose**: Track next available internal ID number for atomic generation

| Field | Type | Constraint | Description |
|-------|------|------------|-------------|
| `id` | BIGINT | PRIMARY KEY | Always 1 (single-row table) |
| `nextValue` | INT | NOT NULL | Next ID number to assign |
| `updatedAt` | TIMESTAMP | NOT NULL | Last modification timestamp |

**Entity Definition**:
```kotlin
@Entity
@Table(name = "requirement_id_sequence")
@Serdeable
data class RequirementIdSequence(
    @Id
    var id: Long = 1L,

    @Column(name = "next_value", nullable = false)
    var nextValue: Int = 1,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
```

**Access Pattern**:
- Single row with `id = 1`
- Use `SELECT ... FOR UPDATE` for atomic increment
- Initialize to `max(existing_requirement_count) + 1` during migration

---

## Relationships

```
┌─────────────────────┐
│    Requirement      │
├─────────────────────┤
│ id (PK)             │
│ internalId (UNIQUE) │◄──────┐
│ versionNumber       │       │ captured as
│ shortreq            │       │ internalId + revision
│ ...                 │       │
└─────────────────────┘       │
         │                    │
         │ snapshots          │
         ▼                    │
┌─────────────────────┐       │
│ RequirementSnapshot │       │
├─────────────────────┤       │
│ id (PK)             │       │
│ originalReqId (FK)  │───────┘
│ internalId          │
│ revision            │
│ release_id (FK)     │
│ ...                 │
└─────────────────────┘
         │
         │ belongs to
         ▼
┌─────────────────────┐
│      Release        │
├─────────────────────┤
│ id (PK)             │
│ version             │
│ name                │
│ status              │
└─────────────────────┘

┌─────────────────────┐
│RequirementIdSequence│
├─────────────────────┤
│ id = 1 (singleton)  │
│ nextValue           │
│ updatedAt           │
└─────────────────────┘
```

---

## State Transitions

### Requirement Lifecycle

```
                    ┌──────────────────┐
                    │    CREATED       │
                    │ (internalId set) │
                    │ (revision = 1)   │
                    └────────┬─────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
              ▼                             ▼
    ┌─────────────────┐          ┌─────────────────┐
    │  CONTENT EDIT   │          │ RELATIONSHIP    │
    │ (revision++)    │          │ EDIT ONLY       │
    │                 │          │ (revision same) │
    └────────┬────────┘          └────────┬────────┘
              │                             │
              └──────────────┬──────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │    DELETED      │
                    │ (ID never reused│
                    └─────────────────┘
```

### Content Fields (trigger revision increment)
- `shortreq`
- `details`
- `example`
- `motivation`
- `usecase`
- `norm`
- `chapter`

### Metadata Fields (do NOT trigger revision increment)
- `usecases` (ManyToMany relationship)
- `norms` (ManyToMany relationship)
- `language`

---

## Database Schema (Flyway Migration)

**File**: `src/backendng/src/main/resources/db/migration/V066__add_requirement_internal_id.sql`

```sql
-- 1. Create sequence table
CREATE TABLE requirement_id_sequence (
    id BIGINT PRIMARY KEY DEFAULT 1,
    next_value INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 2. Add internal_id column to requirement (nullable initially for migration)
ALTER TABLE requirement
ADD COLUMN internal_id VARCHAR(20) NULL;

-- 3. Migrate existing requirements: assign IDs by database ID order
SET @rank := 0;
UPDATE requirement r
JOIN (
    SELECT id, @rank := @rank + 1 AS rn
    FROM requirement
    ORDER BY id ASC
) ranked ON r.id = ranked.id
SET r.internal_id = CONCAT('REQ-', LPAD(ranked.rn, 3, '0'));

-- 4. Initialize sequence to next available value
INSERT INTO requirement_id_sequence (id, next_value, updated_at)
SELECT 1, COALESCE(MAX(CAST(SUBSTRING(internal_id, 5) AS UNSIGNED)), 0) + 1, NOW()
FROM requirement;

-- 5. Make internal_id NOT NULL and add unique constraint
ALTER TABLE requirement
MODIFY COLUMN internal_id VARCHAR(20) NOT NULL,
ADD CONSTRAINT uk_requirement_internal_id UNIQUE (internal_id);

-- 6. Add columns to requirement_snapshot
ALTER TABLE requirement_snapshot
ADD COLUMN internal_id VARCHAR(20) NULL,
ADD COLUMN revision INT NOT NULL DEFAULT 1;

-- 7. Backfill snapshot data from original requirements
UPDATE requirement_snapshot rs
JOIN requirement r ON rs.original_requirement_id = r.id
SET rs.internal_id = r.internal_id,
    rs.revision = r.version_number;

-- 8. Make snapshot internal_id NOT NULL
ALTER TABLE requirement_snapshot
MODIFY COLUMN internal_id VARCHAR(20) NOT NULL;

-- 9. Add index for snapshot lookups by internal_id
CREATE INDEX idx_snapshot_internal_id ON requirement_snapshot(internal_id);
```

---

## Indexes

| Table | Index Name | Columns | Purpose |
|-------|------------|---------|---------|
| requirement | uk_requirement_internal_id | internal_id | Uniqueness enforcement, lookup by ID |
| requirement_snapshot | idx_snapshot_internal_id | internal_id | Fast lookup during release comparison |

---

## Validation Rules Summary

| Entity | Field | Rule |
|--------|-------|------|
| Requirement | internalId | Format: `REQ-\d{3,}`, unique, immutable after creation |
| Requirement | versionNumber | >= 1, increments only on content changes |
| RequirementSnapshot | internalId | Must match source requirement's internalId at snapshot time |
| RequirementSnapshot | revision | Must match source requirement's versionNumber at snapshot time |
| RequirementIdSequence | nextValue | >= 1, monotonically increasing |
