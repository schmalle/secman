# Research: REQADMIN Role for Release Management

**Feature**: 079-reqadmin-release-role
**Date**: 2026-02-06

## Research Findings

### 1. Current Authorization Model

**Decision**: The current codebase uses ADMIN + RELEASE_MANAGER for all release write operations (create, delete, status update). This feature splits authorization: ADMIN + REQADMIN for create/delete, ADMIN + RELEASE_MANAGER for status management.

**Rationale**: The REQADMIN role was added in 078-release-rework but is not yet enforced for release create/delete. The RELEASE_MANAGER role was designed for lifecycle management (alignment, activation), not for the decision of which releases exist.

**Alternatives considered**:
- Keep RELEASE_MANAGER for all operations (rejected: doesn't match the new role separation intent)
- Add REQADMIN alongside RELEASE_MANAGER for create/delete (rejected: spec explicitly says only ADMIN or REQADMIN can create/delete)

### 2. Files Requiring Changes

**Decision**: 10 files need modification, 1 new file (e2e script), 0 new domain entities.

**Backend (3 files)**:
- `ReleaseController.kt`: Lines 34-35 (create) and 135-136 (delete) — change `@Secured("ADMIN", "RELEASE_MANAGER")` to `@Secured("ADMIN", "REQADMIN")`
- `CreateReleaseTool.kt`: Lines 57-63 — change role check from `RELEASE_MANAGER` to `REQADMIN`
- `DeleteReleaseTool.kt`: Lines 47-53 — change role check from `RELEASE_MANAGER` to `REQADMIN`

**Frontend (4 files)**:
- `permissions.ts`: Add `isReqAdmin()` function, update `canCreateRelease()` and `canDeleteRelease()` to use REQADMIN instead of RELEASE_MANAGER
- `ReleaseList.tsx`: Line 71 — change `hasRole('RELEASE_MANAGER')` to `hasRole('REQADMIN')` in `canCreate`
- `ReleaseManagement.tsx`: Add client-side role check for create button (currently missing)
- `ReleaseDetail.tsx`: Uses `canDeleteRelease()` from permissions.ts — will inherit the change

**Docs (1 file)**:
- `docs/MCP.md`: Update role requirements for create_release and delete_release from "ADMIN or RELEASE_MANAGER" to "ADMIN or REQADMIN"

**New (1 file)**:
- `scripts/release-e2e-test.sh`: Comprehensive e2e test with env var authentication

**Rationale**: Minimal change surface. Only authorization logic changes; no business logic, schema, or data model changes.

### 3. Unchanged Files

**Decision**: The following MCP tools keep their ADMIN + RELEASE_MANAGER authorization:
- `ListReleasesTool.kt`
- `GetReleaseTool.kt`
- `SetReleaseStatusTool.kt`
- `CompareReleasesTool.kt`

**Rationale**: Per spec FR-004 and FR-006, status management and read operations remain under ADMIN + RELEASE_MANAGER. The split only applies to create/delete.

**Also unchanged**:
- `ReleaseStatusActions.tsx`: Uses `hasRole(['ADMIN', 'RELEASE_MANAGER'])` for status management — correct per spec FR-008.
- `AlignmentDashboard.tsx`: Uses `hasRole(['ADMIN', 'RELEASE_MANAGER'])` — alignment is status management.

### 4. E2E Test Approach

**Decision**: New bash script at `scripts/release-e2e-test.sh` using direct env vars (SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY) without 1Password dependency.

**Rationale**: The existing `tests/release-e2e-test.sh` requires 1Password CLI (`op`). The new script uses plain environment variables per spec FR-012, making it portable for CI environments.

**Alternatives considered**:
- Modify existing test at `tests/release-e2e-test.sh` (rejected: different authentication approach, different test scope)
- Use 1Password integration (rejected: spec says use env vars directly)

### 5. Ownership-Based Delete Logic

**Decision**: In `canDeleteRelease()`, the ownership check (`release.createdBy === currentUser.username`) currently applies to RELEASE_MANAGER. After this change, it should apply to REQADMIN instead: REQADMIN users can delete only their own non-ACTIVE releases, while ADMIN can delete any non-ACTIVE release.

**Rationale**: This mirrors the existing pattern but transfers ownership-scoped delete from RELEASE_MANAGER to REQADMIN.

### 6. CLAUDE.md Update

**Decision**: CLAUDE.md should be updated to reflect REQADMIN in the role list and release endpoint documentation.

**Rationale**: Constitution Principle VI requires documentation to be updated. CLAUDE.md serves as the agent context file.
