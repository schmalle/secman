# Research: Requirement ID.Revision Versioning

**Feature**: 066-requirement-versioning
**Date**: 2026-01-23

## Research Topics

### 1. ID Generation Strategy

**Decision**: Database sequence table with atomic increment

**Rationale**:
- Single-row `requirement_id_sequence` table ensures atomicity across restarts
- `SELECT FOR UPDATE` + increment provides concurrency safety
- Simpler than database sequences (portable across MySQL/MariaDB versions)
- Matches existing patterns in codebase (no database-specific features)

**Alternatives Considered**:
- **Auto-increment column**: Rejected - IDs would be recycled if requirement deleted and re-created
- **UUID**: Rejected - User explicitly requested "REQ-NNN" format for human readability
- **Database sequence (MariaDB SEQUENCE)**: Rejected - Requires MariaDB 10.3+ specific syntax; table approach is portable

**Implementation Pattern**:
```kotlin
@Transactional
fun getNextId(): String {
    val sequence = repository.findForUpdate() // SELECT ... FOR UPDATE
    val nextVal = sequence.nextValue
    sequence.nextValue = nextVal + 1
    repository.update(sequence)
    return formatId(nextVal) // "REQ-001", "REQ-1000", etc.
}
```

### 2. Revision Tracking Approach

**Decision**: Repurpose existing `versionNumber` field from `VersionedEntity`

**Rationale**:
- Field already exists in database schema (no migration needed for column)
- Already has `incrementVersion()` method
- Reduces schema changes and migration complexity
- Existing `isCurrent` field can track if requirement is "active" version

**Alternatives Considered**:
- **New `revision` column**: Rejected - Redundant with existing infrastructure
- **Audit table with history**: Rejected - Over-engineering; spec only requires revision number, not full change history

**Content Change Detection**:
- Compare field values before update
- Increment only if: shortreq, details, example, motivation, usecase, norm, or chapter changed
- Do NOT increment for: usecases relationship, norms relationship changes (per FR-005)

### 3. ID Format and Zero-Padding

**Decision**: "REQ-NNN" with minimum 3 digits, auto-extend to 4+ when needed

**Rationale**:
- 3 digits covers 999 requirements (typical use case)
- Auto-extend (REQ-1000, REQ-10000) handles growth without format change
- "REQ-" prefix distinguishes from database IDs in exports and UI

**Implementation**:
```kotlin
fun formatId(num: Int): String {
    return if (num < 1000) {
        "REQ-${num.toString().padStart(3, '0')}"
    } else {
        "REQ-$num"
    }
}
```

### 4. Migration Strategy for Existing Requirements

**Decision**: Assign IDs by database ID order during Flyway migration

**Rationale**:
- Clarification confirmed: database ID order (lowest → REQ-001)
- Single migration script handles both schema and data
- Deterministic and reproducible

**Migration Steps**:
1. Create `requirement_id_sequence` table
2. Add `internal_id` column to `requirement` table (nullable initially)
3. UPDATE requirements SET internal_id = CONCAT('REQ-', LPAD(rank, 3, '0')) ordered by id
4. Set `requirement_id_sequence.next_value` to max(rank) + 1
5. ALTER `internal_id` to NOT NULL with UNIQUE constraint
6. Add `internal_id` and `revision` columns to `requirement_snapshot`

### 5. Snapshot Storage

**Decision**: Store both `internalId` and `revision` in RequirementSnapshot

**Rationale**:
- Snapshots must preserve ID.Revision at release time
- Current `originalRequirementId` is database ID, not internal ID
- Adding fields enables accurate historical diff comparison

**Schema Change**:
```sql
ALTER TABLE requirement_snapshot
  ADD COLUMN internal_id VARCHAR(20),
  ADD COLUMN revision INT DEFAULT 1;
```

### 6. Export Format Changes

**Decision**: ID.Revision as first column in Excel, prefix in Word headers

**Excel Changes**:
- Current headers: `Chapter, Norm, Short req, DetailsEN, MotivationEN, ExampleEN, UseCase`
- New headers: `ID.Revision, Chapter, Norm, Short req, DetailsEN, MotivationEN, ExampleEN, UseCase`
- For release exports: use snapshot's `internalId` and `revision`

**Word Changes**:
- Current: `Req N: {shortreq}`
- New: `{ID.Revision}: {shortreq}` (e.g., "REQ-001.3: The system shall...")

### 7. API Response Backward Compatibility

**Decision**: Add fields to existing responses; do not remove any fields

**Rationale**:
- Constitution requires backward compatibility within major versions
- Existing consumers won't break when new fields appear

**Changes to RequirementResponse**:
```kotlin
data class RequirementResponse(
    val id: Long,                    // Keep: database ID
    val internalId: String,          // Add: "REQ-001"
    val revision: Int,               // Add: 3
    val idRevision: String,          // Add: "REQ-001.3"
    // ... existing fields unchanged
)
```

## Dependencies Verified

| Dependency | Version | Usage | Status |
|------------|---------|-------|--------|
| Apache POI | 5.3 | Excel/Word export modification | Already present |
| Micronaut Data | 4.10 | Repository queries | Already present |
| Hibernate JPA | 6.x | Entity mapping, @PreUpdate | Already present |

## Best Practices Applied

1. **Atomic ID Generation**: Use `SELECT FOR UPDATE` to prevent race conditions
2. **Immutable IDs**: Never change `internalId` after assignment
3. **Revision Semantics**: Only increment on meaningful content changes
4. **Migration Safety**: Run migration in transaction; rollback if any step fails
5. **Export Consistency**: Include ID.Revision in all export formats (standard, release, translated)

## Open Questions Resolved

| Question | Resolution |
|----------|------------|
| Migration order for existing requirements | Database ID order (lowest → REQ-001) per clarification session |
| Reuse `versionNumber` or add `revision`? | Reuse existing `versionNumber` field |
| What triggers revision increment? | Content field changes only (FR-004), not relationship changes (FR-005) |
