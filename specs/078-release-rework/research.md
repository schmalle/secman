# Research: Release Rework

**Feature**: 078-release-rework
**Date**: 2026-02-06

## R1: Status Enum Rename Strategy

**Decision**: Rename enum values in-place in the Kotlin domain, use Hibernate auto-migration + Flyway SQL to update existing DB rows.

**Rationale**: The Release entity uses `@Enumerated(EnumType.STRING)`, so status values are stored as string literals in MariaDB. Renaming the Kotlin enum alone would break deserialization of existing rows. A one-time SQL UPDATE is needed.

**Mapping**:
- `DRAFT` → `PREPARATION`
- `IN_REVIEW` → `ALIGNMENT`
- `ACTIVE` → `ACTIVE` (no change)
- `LEGACY` → `ARCHIVED`
- `PUBLISHED` → `ARCHIVED`

**Alternatives considered**:
- Adding new enum values while keeping old ones as aliases: Rejected — increases complexity and leaves stale values in code.
- Creating a new column and deprecating old: Rejected — overkill for a simple enum rename.

## R2: Frontend Status References

**Decision**: Update all frontend string literals (`DRAFT`, `IN_REVIEW`, `LEGACY`, `PUBLISHED`) to the new names in all components.

**Rationale**: The frontend uses hardcoded status strings in ReleaseManagement.tsx, ReleaseStatusActions.tsx, ReleaseSelector.tsx, ReleaseList.tsx, and releaseService.ts. All must be updated atomically.

**Files requiring status string changes**:
- `ReleaseManagement.tsx` — badge classes, CRUD logic
- `ReleaseStatusActions.tsx` — transition buttons, modal text
- `ReleaseSelector.tsx` — status display in dropdown
- `ReleaseList.tsx` — status badges, action buttons
- `releaseService.ts` — TypeScript types/interfaces
- `ReleaseDeleteConfirm.tsx` — deletion guard
- `ReleaseCreateModal.tsx` — creation defaults

## R3: Release Context Switching Architecture

**Decision**: Reuse existing pattern where `selectedReleaseId` state drives API calls in RequirementManagement.tsx.

**Rationale**: RequirementManagement.tsx already has `selectedReleaseId` state, `isViewingHistorical` flag, and conditional fetch logic (snapshot endpoint vs live endpoint). The change is:
1. Default to ACTIVE release's ID instead of null (live).
2. When no ACTIVE release exists, fall back to null (live) with indicator.
3. Persist `selectedReleaseId` in sessionStorage (FR-015).

**Current behavior**: null = live requirements, number = release snapshot.
**New behavior**: On page load, fetch ACTIVE release ID and set as default. User can switch to any release.

## R4: Export Endpoint Compatibility

**Decision**: Existing export endpoints already support `releaseId` query parameter. No backend changes needed for basic export. Frontend must always pass the selected release context.

**Rationale**: RequirementController already handles:
- `GET /api/requirements/export/docx?releaseId={id}` → snapshot export
- `GET /api/requirements/export/xlsx?releaseId={id}` → snapshot export
- Translated variants also support `?releaseId={id}`

**Gap found**: Use case filtered exports (`/export/docx/usecase/{usecaseId}`) do NOT currently support `releaseId`. This needs backend work if FR-012 (all formats) is to be satisfied. Snapshots store `usecaseIdsSnapshot` as JSON strings, so filtering is possible but requires a new code path.

## R5: AlignmentService Status Transition Update

**Decision**: Update AlignmentService to use ALIGNMENT instead of IN_REVIEW, and PREPARATION instead of DRAFT.

**Rationale**: AlignmentService.kt directly sets `release.status = ReleaseStatus.IN_REVIEW` (line ~141) and checks for DRAFT status. These references must change to ALIGNMENT and PREPARATION respectively.

**Key locations**:
- `startAlignment()`: Changes DRAFT → IN_REVIEW (becomes PREPARATION → ALIGNMENT)
- `finalizeAlignment()`: Optionally transitions IN_REVIEW → ACTIVE (becomes ALIGNMENT → ACTIVE)
- `cancelAlignment()`: Returns IN_REVIEW → DRAFT (becomes ALIGNMENT → PREPARATION)

## R6: MCP Tool Status Updates

**Decision**: Update all MCP release tools to use the new status names.

**Files**:
- `SetReleaseStatusTool.kt` — accepts "ACTIVE" only (no change needed, but validation text and allowed transitions need updating)
- `CreateReleaseTool.kt` — returns status as "DRAFT" (becomes "PREPARATION")
- `ListReleasesTool.kt` — filter accepts status strings (update validation)
- `GetReleaseTool.kt` — returns status string (automatic via enum)
- `DeleteReleaseTool.kt` — blocks ACTIVE deletion (no change needed)
- `CompareReleasesTool.kt` — no status logic

## R7: E2E Test Approach

**Decision**: Create `tests/release-e2e-test.sh` following the pattern from `tests/mcp-e2e-workgroup-test.sh`.

**Rationale**: Existing E2E tests use curl + jq with JSON-RPC 2.0 MCP calls. The release test will:
1. Authenticate via JWT login + use MCP API key
2. Create test requirements via `add_requirement`
3. Create Release 1.0 via `create_release` (PREPARATION status)
4. Activate Release 1.0 via `set_release_status` (→ ACTIVE)
5. Modify requirement via `add_requirement` (update)
6. Create Release 2.0 via `create_release` (PREPARATION status)
7. Verify `get_release` with `includeRequirements` returns correct snapshots for each release
8. Compare releases via `compare_releases` to verify diffs
9. Export from each release and verify file download succeeds
10. Cleanup: delete releases, delete requirements

**Environment variables**: SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY

## R8: Database Migration

**Decision**: Create a Flyway migration script for the status column update.

**SQL**:
```sql
UPDATE releases SET status = 'PREPARATION' WHERE status = 'DRAFT';
UPDATE releases SET status = 'ALIGNMENT' WHERE status = 'IN_REVIEW';
UPDATE releases SET status = 'ARCHIVED' WHERE status IN ('LEGACY', 'PUBLISHED');
```

**Rationale**: Constitution Principle VI (Schema Evolution) requires Flyway migration scripts. Hibernate auto-migration handles DDL (column definitions), but DML (data updates) must be in Flyway.
