# Data Model: Release Rework

**Feature**: 078-release-rework
**Date**: 2026-02-06

## Entity Changes

### Release (existing — modified)

**Change**: Rename `ReleaseStatus` enum values.

| Old Status | New Status | Description |
|-----------|-----------|-------------|
| DRAFT | PREPARATION | New release, being prepared for alignment |
| IN_REVIEW | ALIGNMENT | Alignment process active, reviewers assessing changes |
| ACTIVE | ACTIVE | Current active release (only one at a time) |
| LEGACY | ARCHIVED | Previously active, now archived |
| PUBLISHED | ARCHIVED | (Merged into ARCHIVED) |

**State Machine**:

```
                    ┌──cancelAlignment()──┐
                    ▼                     │
PREPARATION ──startAlignment()──▶ ALIGNMENT
     │                                │
     │                                │ finalizeAlignment(activate=true)
     │                                ▼
     └──setStatus(ACTIVE)──────▶  ACTIVE
                                    │
                                    │ (automatic when another release becomes ACTIVE)
                                    ▼
                                 ARCHIVED
```

**Valid transitions**:
- PREPARATION → ALIGNMENT (via alignment start)
- PREPARATION → ACTIVE (direct activation, skipping alignment)
- ALIGNMENT → ACTIVE (via alignment finalization with activate=true)
- ALIGNMENT → PREPARATION (via alignment cancellation)
- ACTIVE → ARCHIVED (automatic only, when another release is activated)

**Invalid transitions**:
- ARCHIVED → any (terminal state)
- ACTIVE → any manual change (only automatic demotion to ARCHIVED)

**Constraints**:
- Exactly one ACTIVE release at any time (or zero during initial setup)
- ACTIVE releases cannot be deleted

### RequirementSnapshot (existing — no changes)

No schema changes. Existing snapshot structure is sufficient.

### Requirement (existing — no changes)

No schema changes. Version tracking via `versionNumber` field is preserved.

## Database Migration

**Flyway script** (e.g., `V078__release_status_rework.sql`):

```sql
-- Rename release statuses
UPDATE releases SET status = 'PREPARATION' WHERE status = 'DRAFT';
UPDATE releases SET status = 'ALIGNMENT' WHERE status = 'IN_REVIEW';
UPDATE releases SET status = 'ARCHIVED' WHERE status IN ('LEGACY', 'PUBLISHED');
```

No DDL changes required — the `status` column is `VARCHAR` storing enum string names. The Kotlin enum change + Flyway DML is sufficient.
