# Implementation Plan: Sort Empty AWS Accounts to Bottom

**Branch**: `075-sort-empty-accounts` | **Date**: 2026-02-05 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/075-sort-empty-accounts/spec.md`

## Summary

Sort AWS accounts on the Account Vulns page so accounts with assets appear first and accounts with no assets are grouped at the bottom in a collapsible section (collapsed by default). This is a frontend-only change to `AccountVulnsView.tsx` — the backend already returns all data needed (including `totalAssets` per account group). No API changes required.

## Technical Context

**Language/Version**: TypeScript / React 19
**Primary Dependencies**: Astro 5.15, React 19, Bootstrap 5.3
**Storage**: N/A (no data model changes)
**Testing**: N/A (per constitution — user-requested only)
**Target Platform**: Web browser
**Project Type**: Web application (frontend only)
**Performance Goals**: No additional network requests; sorting is client-side on existing data
**Constraints**: Must work with existing API response shape; no backend changes
**Scale/Scope**: Single component modification (`AccountVulnsView.tsx`)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | No new inputs, no file uploads, no auth changes |
| III. API-First | PASS | No API changes — frontend-only sorting |
| IV. User-Requested Testing | PASS | No tests planned unless requested |
| V. RBAC | PASS | No access control changes; existing auth unaffected |
| VI. Schema Evolution | PASS | No database changes |

All gates pass. No violations to track.

## Project Structure

### Documentation (this feature)

```text
specs/075-sort-empty-accounts/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (files touched)

```text
src/frontend/src/
├── components/
│   └── AccountVulnsView.tsx    # Main change: sort logic + collapsible section
└── (no other files affected)
```

**Structure Decision**: Frontend-only change. The single file `AccountVulnsView.tsx` contains the rendering loop for account groups (line 290) where the sort and collapsible section will be applied. No new files needed. `AssetVulnTable.tsx` and `accountVulnsService.ts` remain unchanged.

## Complexity Tracking

> No violations. Table intentionally left empty.
