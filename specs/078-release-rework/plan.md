# Implementation Plan: Release Rework

**Branch**: `078-release-rework` | **Date**: 2026-02-06 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/078-release-rework/spec.md`

## Summary

Rework the release status model from 5 states (DRAFT, IN_REVIEW, ACTIVE, LEGACY, PUBLISHED) to 4 states (PREPARATION, ALIGNMENT, ACTIVE, ARCHIVED). Make release context the default view mode — users always see requirements through a release. Update all backend services, MCP tools, frontend components, and exports to use the new statuses. Add Flyway migration for existing data. Create an E2E test suite.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25 (backend), TypeScript / React 19 (frontend)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, Astro 5.15, Bootstrap 5.3, Axios
**Storage**: MariaDB 11.4 (existing `releases` table, `requirement_snapshot` table)
**Testing**: E2E shell script (curl + jq + MCP JSON-RPC), `./gradlew build` (backend), `npm run build` (frontend)
**Target Platform**: Linux server (backend), Web browser (frontend)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: Release context switching < 2 seconds (SC-001)
**Constraints**: Backward-incompatible status rename requires Flyway migration
**Scale/Scope**: ~4 releases, ~168 requirement snapshots per release, ~10 concurrent users

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | RBAC enforced via @Secured + role checks. No new user input surfaces. |
| III. API-First | PASS | REST endpoints unchanged structurally. Status values change (breaking, documented). |
| IV. User-Requested Testing | PASS | E2E test is explicitly requested in spec (FR-014). |
| V. RBAC | PASS | ADMIN/RELEASE_MANAGER required for status transitions. All authenticated users can view. Edit blocking for ARCHIVED/ACTIVE releases enforced in UI. |
| VI. Schema Evolution | PASS | Flyway migration script for status value rename. No DDL changes. |

**Post-Phase 1 re-check**: All gates still pass. No new violations introduced by design artifacts.

## Project Structure

### Documentation (this feature)

```text
specs/078-release-rework/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: research findings
├── data-model.md        # Phase 1: entity changes
├── quickstart.md        # Phase 1: verification guide
├── contracts/           # Phase 1: API contract changes
│   └── api-changes.md
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── domain/
│   └── Release.kt                          # ReleaseStatus enum rename
├── service/
│   ├── ReleaseService.kt                   # Transition logic updates
│   └── AlignmentService.kt                 # DRAFT→PREPARATION, IN_REVIEW→ALIGNMENT
├── controller/
│   ├── ReleaseController.kt                # Enum-driven, minimal changes
│   └── RequirementController.kt            # Add releaseId to use-case export endpoints
├── mcp/tools/
│   ├── CreateReleaseTool.kt                # Status text updates
│   ├── SetReleaseStatusTool.kt             # Validation text
│   ├── ListReleasesTool.kt                 # Filter validation
│   ├── GetReleaseTool.kt                   # Enum-driven
│   └── DeleteReleaseTool.kt                # Enum-driven
└── resources/db/migration/
    └── V078__release_status_rework.sql     # Flyway DML migration

src/frontend/src/
├── components/
│   ├── ReleaseSelector.tsx                 # Default to ACTIVE, sessionStorage
│   ├── RequirementManagement.tsx           # Default release context, edit guards
│   ├── Export.tsx                           # Always pass selected release
│   ├── ReleaseManagement.tsx               # Status names, badge classes
│   ├── ReleaseStatusActions.tsx            # Transition labels
│   ├── ReleaseList.tsx                     # Status badges
│   ├── ReleaseCreateModal.tsx             # DRAFT→PREPARATION status text
│   ├── ReleaseDetail.tsx                  # Status switch cases rename
│   └── AlignmentDashboard.tsx             # DRAFT→PREPARATION status text
└── services/
    └── releaseService.ts                   # TypeScript types

tests/
└── release-e2e-test.sh                    # New E2E test script
```

**Structure Decision**: Existing web application structure (backend + frontend). No new directories needed. Changes are modifications to existing files plus one new Flyway migration and one new E2E test script.

## Complexity Tracking

No constitution violations. No complexity justifications needed.
