# Quickstart: Requirement ID.Revision Versioning

**Feature**: 066-requirement-versioning
**Date**: 2026-01-23

## Overview

This guide provides a quick reference for implementing the requirement ID.Revision versioning feature.

## Prerequisites

- Existing secman development environment
- MariaDB 11.4+ running
- Backend and frontend projects building successfully

## Implementation Order

### Phase 1: Database Migration
1. Create Flyway migration `V066__add_requirement_internal_id.sql`
2. Run migration to add `internal_id` column and sequence table
3. Verify existing requirements have IDs assigned

### Phase 2: Backend Entity Changes
1. Add `internalId` field to `Requirement.kt`
2. Add `internalId`, `revision` fields to `RequirementSnapshot.kt`
3. Create `RequirementIdSequence.kt` entity
4. Create `RequirementIdSequenceRepository.kt`

### Phase 3: Service Layer
1. Create `RequirementIdService.kt` for atomic ID generation
2. Update `RequirementService.kt` to assign IDs on creation
3. Add revision increment logic to update method

### Phase 4: API Updates
1. Update `RequirementResponse` DTO with new fields
2. Update export methods (Excel, Word) to include ID.Revision
3. Update comparison DTOs with revision info

### Phase 5: Frontend Updates
1. Display ID.Revision in requirement list/detail views
2. Update diff view to show revision changes
3. Add tooltip showing last modified date

## Key Code Snippets

### ID Generation Service
```kotlin
@Singleton
open class RequirementIdService(
    private val sequenceRepository: RequirementIdSequenceRepository
) {
    @Transactional
    open fun getNextId(): String {
        val sequence = sequenceRepository.findByIdForUpdate(1L)
            ?: throw IllegalStateException("ID sequence not initialized")
        val nextVal = sequence.nextValue
        sequence.nextValue = nextVal + 1
        sequenceRepository.update(sequence)
        return formatId(nextVal)
    }

    private fun formatId(num: Int): String =
        if (num < 1000) "REQ-${num.toString().padStart(3, '0')}"
        else "REQ-$num"
}
```

### Revision Increment Logic
```kotlin
// In RequirementService.updateRequirement()
private fun shouldIncrementRevision(existing: Requirement, update: RequirementUpdateRequest): Boolean {
    return update.shortreq != existing.shortreq ||
           update.details != existing.details ||
           update.example != existing.example ||
           update.motivation != existing.motivation ||
           update.usecase != existing.usecase ||
           update.norm != existing.norm ||
           update.chapter != existing.chapter
}

// Apply before save:
if (shouldIncrementRevision(existing, request)) {
    existing.incrementVersion() // Uses VersionedEntity method
}
```

### Excel Export Column Addition
```kotlin
// In createExcelWorkbook()
val headers = arrayOf(
    "ID.Revision",  // NEW - first column
    "Chapter", "Norm", "Short req", "DetailsEN", "MotivationEN", "ExampleEN", "UseCase"
)

// Data row:
row.createCell(0).setCellValue(requirement.idRevision)  // e.g., "REQ-001.3"
```

### Word Export Header Change
```kotlin
// In createWordDocument()
// OLD: reqHeaderRun.setText("Req $requirementNumber: ${requirement.shortreq}")
// NEW:
reqHeaderRun.setText("${requirement.idRevision}: ${requirement.shortreq}")
```

### Frontend Display
```tsx
// In RequirementManagement.tsx
<td>
  <span
    className="badge bg-secondary"
    title={`Last modified: ${requirement.updatedAt}`}
  >
    {requirement.idRevision}
  </span>
</td>
```

## Testing Checklist

- [ ] New requirement gets ID (REQ-001, REQ-002, etc.)
- [ ] Editing content increments revision (REQ-001.1 → REQ-001.2)
- [ ] Editing relationships does NOT increment revision
- [ ] ID never changes after creation
- [ ] Deleted ID is not reused
- [ ] Excel export shows ID.Revision as first column
- [ ] Word export shows ID.Revision in headers
- [ ] Release snapshot captures ID.Revision correctly
- [ ] Release comparison shows revision changes

## Common Issues

### ID Sequence Not Found
Ensure Flyway migration ran and inserted initial row:
```sql
SELECT * FROM requirement_id_sequence;
-- Should show: id=1, next_value=N (where N = existing req count + 1)
```

### Revision Not Incrementing
Check that content fields are actually changing, not just relationships:
- Content fields: shortreq, details, example, motivation, usecase, norm, chapter
- Relationship fields (no increment): usecases, norms ManyToMany

### Export Shows Wrong ID.Revision
For release exports, ensure you're using snapshot's `internalId` and `revision`, not current requirement values.

## Files Changed Summary

| File | Change Type |
|------|-------------|
| `Requirement.kt` | Modified (add internalId) |
| `RequirementSnapshot.kt` | Modified (add internalId, revision) |
| `RequirementIdSequence.kt` | New entity |
| `RequirementIdSequenceRepository.kt` | New repository |
| `RequirementIdService.kt` | New service |
| `RequirementService.kt` | Modified (ID assignment, revision logic) |
| `RequirementController.kt` | Modified (response DTOs, exports) |
| `RequirementManagement.tsx` | Modified (display ID.Revision) |
| `ReleaseComparison.tsx` | Modified (show revisions in diff) |
| `comparisonExport.ts` | Modified (export columns) |
| `V066__add_requirement_internal_id.sql` | New migration |
