# Implementation Plan: REQADMIN Role for Release Management

**Branch**: `079-reqadmin-release-role` | **Date**: 2026-02-06 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/079-reqadmin-release-role/spec.md`

## Summary

Enforce the REQADMIN role as the required authorization (alongside ADMIN) for release creation and deletion across all layers: REST API endpoints, MCP tools, and frontend UI. RELEASE_MANAGER retains status management authority but loses create/delete permissions. Update MCP documentation and create a comprehensive e2e test script.

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25 (backend), TypeScript / React 19 (frontend), Bash (e2e test)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, Astro 5.15, Bootstrap 5.3
**Storage**: MariaDB 11.4 (no schema changes needed - authorization-only change)
**Testing**: Bash e2e test script with curl/jq
**Target Platform**: Linux server (backend), Web browser (frontend)
**Project Type**: Web application (backend + frontend)
**Performance Goals**: N/A (authorization policy change, no performance impact)
**Constraints**: Backward compatibility for read-only operations; RELEASE_MANAGER must keep status management
**Scale/Scope**: 6 MCP tools, 1 REST controller (6 endpoints), 4 frontend components, 1 permissions utility, MCP docs, e2e test

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | RBAC enforced at API (@Secured), MCP (programmatic check), and UI (hasRole) layers |
| III. API-First | PASS | No breaking changes to API contract; only role requirements updated |
| IV. User-Requested Testing | PASS | E2e test is explicitly requested in the spec |
| V. RBAC | PASS (with update needed) | Constitution lists roles: USER, ADMIN, VULN, RELEASE_MANAGER. REQADMIN must be added to constitution's RBAC principle. |
| VI. Schema Evolution | PASS | No schema changes required |

**Constitution Update Required**: Principle V lists roles as "USER, ADMIN, VULN, RELEASE_MANAGER". The REQADMIN role (added in 078-release-rework) should be added to this list. This is a documentation update, not a violation.

## Project Structure

### Documentation (this feature)

```text
specs/079-reqadmin-release-role/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── authorization-matrix.md
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── controller/
│   └── ReleaseController.kt          # @Secured annotations: RELEASE_MANAGER → REQADMIN (create/delete)
├── mcp/tools/
│   ├── CreateReleaseTool.kt           # Role check: RELEASE_MANAGER → REQADMIN
│   └── DeleteReleaseTool.kt           # Role check: RELEASE_MANAGER → REQADMIN
│   # Unchanged: ListReleasesTool, GetReleaseTool, SetReleaseStatusTool, CompareReleasesTool

src/frontend/src/
├── utils/
│   └── permissions.ts                 # canCreateRelease, canDeleteRelease: add isReqAdmin()
├── components/
│   ├── ReleaseList.tsx                # canCreate check: add REQADMIN
│   ├── ReleaseManagement.tsx          # Add client-side REQADMIN check for create
│   └── ReleaseDetail.tsx              # Delete button: canDeleteRelease uses permissions.ts

docs/
└── MCP.md                             # Update role requirements for create/delete tools

scripts/
└── release-e2e-test.sh                # New comprehensive e2e test
```

**Structure Decision**: Existing web application structure. This feature modifies authorization logic in existing files across backend, MCP, and frontend layers. No new source files needed. One new test script and documentation updates.

## Complexity Tracking

No constitution violations to justify.
