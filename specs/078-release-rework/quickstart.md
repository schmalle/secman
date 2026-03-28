# Quickstart: Release Rework

**Feature**: 078-release-rework
**Date**: 2026-02-06

## What This Feature Does

Reworks the release status model from 5 states (DRAFT, IN_REVIEW, ACTIVE, LEGACY, PUBLISHED) to 4 states (PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED). Adds release context switching so users always view requirements through a release lens, and ensures exports respect the selected release context.

## Files Modified

### Backend (status model + migration)
- `src/backendng/src/main/kotlin/com/secman/domain/Release.kt` — ReleaseStatus enum rename
- `src/backendng/src/main/kotlin/com/secman/service/ReleaseService.kt` — transition logic update
- `src/backendng/src/main/kotlin/com/secman/service/AlignmentService.kt` — DRAFT/IN_REVIEW → PREPARATION/ALIGNMENT
- `src/backendng/src/main/kotlin/com/secman/controller/ReleaseController.kt` — no logic changes (enum-driven)
- `src/backendng/src/main/kotlin/com/secman/controller/ReleaseComparisonController.kt` — no changes
- `src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateReleaseTool.kt` — status text updates
- `src/backendng/src/main/kotlin/com/secman/mcp/tools/SetReleaseStatusTool.kt` — validation text
- `src/backendng/src/main/kotlin/com/secman/mcp/tools/ListReleasesTool.kt` — filter validation
- `src/backendng/src/main/resources/db/migration/V078__release_status_rework.sql` — DML migration

### Backend (use-case export releaseId support)
- `src/backendng/src/main/kotlin/com/secman/controller/RequirementController.kt` — add releaseId parameter to use-case filtered export endpoints

### Frontend (UI status names + context switching)
- `src/frontend/src/components/ReleaseSelector.tsx` — default to ACTIVE, sessionStorage
- `src/frontend/src/components/RequirementManagement.tsx` — default release context, edit guards
- `src/frontend/src/components/Export.tsx` — always pass selected release
- `src/frontend/src/components/ReleaseManagement.tsx` — status names, badge classes
- `src/frontend/src/components/ReleaseStatusActions.tsx` — transition labels
- `src/frontend/src/components/ReleaseList.tsx` — status badges
- `src/frontend/src/components/ReleaseCreateModal.tsx` — DRAFT→PREPARATION status text
- `src/frontend/src/components/ReleaseDetail.tsx` — status switch cases rename
- `src/frontend/src/components/AlignmentDashboard.tsx` — DRAFT→PREPARATION status text
- `src/frontend/src/services/releaseService.ts` — TypeScript types

### Documentation
- `docs/MCP.md` — update status values in release tool descriptions
- `CLAUDE.md` — update release status list

### Tests
- `tests/release-e2e-test.sh` — new E2E test script

## How to Verify

### Backend
```bash
./gradlew build
```

### Frontend
```bash
cd src/frontend && npm run build
```

### E2E Tests
```bash
export SECMAN_ADMIN_NAME=adminuser
export SECMAN_ADMIN_PASS=password
export SECMAN_API_KEY=sk-your-api-key
./tests/release-e2e-test.sh
```

## Key Behavioral Changes

1. **New releases** are created with status `PREPARATION` (was `DRAFT`)
2. **Alignment** transitions to `ALIGNMENT` (was `IN_REVIEW`)
3. **Old releases** become `ARCHIVED` (was `LEGACY`/`PUBLISHED`)
4. **Default view** shows ACTIVE release requirements (was live/current)
5. **No ACTIVE release** falls back to live requirements with indicator
6. **Editing blocked** for ARCHIVED and ACTIVE releases; allowed for PREPARATION and ALIGNMENT
7. **Session persistence** of selected release via sessionStorage
