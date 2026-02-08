# Tasks: REQADMIN Role for Release Management

**Input**: Design documents from `/specs/079-reqadmin-release-role/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: E2E test script is explicitly requested in spec (User Story 5). No unit/integration test tasks generated per constitution Principle IV (User-Requested Testing).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Phase 1: Setup

**Purpose**: No project initialization needed — all infrastructure exists. This feature modifies authorization logic in existing files only.

No setup tasks required.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Add the `isReqAdmin()` utility function that multiple user stories depend on.

- [X] T001 Add `isReqAdmin()` function to `src/frontend/src/utils/permissions.ts` after `isReleaseManager()` — checks if roles array includes 'REQADMIN'

**Checkpoint**: Foundation ready — user story implementation can now begin.

---

## Phase 3: User Story 1+2 — Backend REST Endpoint Authorization (Priority: P1)

**Goal**: Change @Secured annotations on create/delete release endpoints from RELEASE_MANAGER to REQADMIN. Status management endpoints remain unchanged.

**Independent Test**: Call POST /api/releases and DELETE /api/releases/{id} with REQADMIN Bearer token — expect success. Call same endpoints with RELEASE_MANAGER token — expect 403.

### Implementation

- [X] T002 [US1] Update `@Secured("ADMIN", "RELEASE_MANAGER")` to `@Secured("ADMIN", "REQADMIN")` on `createRelease()` method in `src/backendng/src/main/kotlin/com/secman/controller/ReleaseController.kt` (line ~35)
- [X] T003 [US1] Update `@Secured("ADMIN", "RELEASE_MANAGER")` to `@Secured("ADMIN", "REQADMIN")` on `deleteRelease()` method in `src/backendng/src/main/kotlin/com/secman/controller/ReleaseController.kt` (line ~136)

**Checkpoint**: REST API now enforces REQADMIN for create/delete. Status management (PUT /api/releases/{id}/status) still requires ADMIN/RELEASE_MANAGER.

---

## Phase 4: User Story 3 — MCP Tool Authorization (Priority: P1)

**Goal**: Update create_release and delete_release MCP tools to require REQADMIN instead of RELEASE_MANAGER. Other MCP tools unchanged.

**Independent Test**: Call create_release/delete_release MCP tools with REQADMIN user delegation — expect success. Call with RELEASE_MANAGER delegation — expect AUTHORIZATION_ERROR.

### Implementation

- [X] T004 [P] [US3] Update role check in `src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateReleaseTool.kt` — change `!userRoles.contains("RELEASE_MANAGER")` to `!userRoles.contains("REQADMIN")` and update error message to "ADMIN or REQADMIN role required to create releases"
- [X] T005 [P] [US3] Update role check in `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteReleaseTool.kt` — change `!userRoles.contains("RELEASE_MANAGER")` to `!userRoles.contains("REQADMIN")` and update error message to "ADMIN or REQADMIN role required to delete releases"

**Checkpoint**: MCP tools now enforce REQADMIN for create/delete. list_releases, get_release, set_release_status, compare_releases remain ADMIN/RELEASE_MANAGER.

---

## Phase 5: User Story 4 — Frontend UI REQADMIN Enforcement (Priority: P2)

**Goal**: Show create/delete controls only to ADMIN/REQADMIN users. Status management controls remain visible to ADMIN/RELEASE_MANAGER.

**Independent Test**: Login as REQADMIN user — see Create/Delete buttons. Login as RELEASE_MANAGER (no ADMIN/REQADMIN) — do NOT see Create/Delete buttons but still see status management.

### Implementation

- [X] T006 [US4] Update `canCreateRelease()` in `src/frontend/src/utils/permissions.ts` — replace `isReleaseManager(roles)` with `isReqAdmin(roles)` so it returns `isAdmin(roles) || isReqAdmin(roles)`
- [X] T007 [US4] Update `canDeleteRelease()` in `src/frontend/src/utils/permissions.ts` — replace the RELEASE_MANAGER ownership check block (lines ~190-193) with REQADMIN ownership check: `if (isReqAdmin(currentUserRoles)) { return release.createdBy === currentUser.username; }`
- [X] T008 [US4] Update `canCreate` variable in `src/frontend/src/components/ReleaseList.tsx` (line ~71) — change `hasRole('RELEASE_MANAGER')` to `hasRole('REQADMIN')`
- [X] T009 [US4] Add client-side role check in `src/frontend/src/components/ReleaseManagement.tsx` — add `const canCreate = typeof window !== 'undefined' && (hasRole('ADMIN') || hasRole('REQADMIN'));` and conditionally render create button based on `canCreate`

**Checkpoint**: Frontend now shows create/delete controls only to ADMIN/REQADMIN. RELEASE_MANAGER still sees status management controls (ReleaseStatusActions unchanged).

---

## Phase 6: User Story 5 — End-to-End Test Suite (Priority: P2)

**Goal**: Create comprehensive bash e2e test script that validates the complete release lifecycle with REQADMIN role enforcement.

**Independent Test**: Run `./scripts/release-e2e-test.sh` with SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY env vars set — all tests pass.

### Implementation

- [X] T010 [US5] Create `scripts/release-e2e-test.sh` — bash script with: env var authentication (SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY), helper functions (assert_equals, assert_not_empty, assert_http_ok, mcp_call, rest_get), test steps for: create requirements, create release (PREPARATION), activate release (ACTIVE), modify requirement, create second release, verify snapshots, compare releases, export (Word/Excel), cleanup, and pass/fail summary report. Make executable.

**Checkpoint**: E2E test validates complete release lifecycle including authorization.

---

## Phase 7: User Story 6 — MCP Documentation (Priority: P3)

**Goal**: Update MCP documentation to reflect REQADMIN role for create/delete tools.

**Independent Test**: Read docs/MCP.md and verify create_release and delete_release state "ADMIN or REQADMIN" while other tools state "ADMIN or RELEASE_MANAGER".

### Implementation

- [X] T011 [US6] Update `docs/MCP.md` — change role requirements for `create_release` tool from "ADMIN or RELEASE_MANAGER" to "ADMIN or REQADMIN" in the Release Management Tools section
- [X] T012 [P] [US6] Update `docs/MCP.md` — change role requirements for `delete_release` tool from "ADMIN or RELEASE_MANAGER" to "ADMIN or REQADMIN" in the Release Management Tools section
- [X] T013 [US6] Update permission mapping table in `docs/MCP.md` if it references RELEASE_MANAGER for create/delete tools — change to REQADMIN

**Checkpoint**: MCP documentation accurately reflects authorization requirements.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Build verification and documentation updates.

- [X] T014 Run `./gradlew build` to verify backend compiles without errors
- [X] T015 Update `CLAUDE.md` — add REQADMIN to the Security/RBAC role list and update release endpoint documentation to reflect ADMIN/REQADMIN for create/delete

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: Empty — no setup needed
- **Phase 2 (Foundational)**: T001 — BLOCKS Phase 5 (frontend) only
- **Phase 3 (US1+2 REST)**: No dependencies — can start immediately
- **Phase 4 (US3 MCP)**: No dependencies — can start immediately, parallel with Phase 3
- **Phase 5 (US4 Frontend)**: Depends on T001 (isReqAdmin function)
- **Phase 6 (US5 E2E Test)**: Depends on Phase 3 + Phase 4 (backend must be updated first)
- **Phase 7 (US6 Docs)**: No dependencies — can start anytime
- **Phase 8 (Polish)**: Depends on all implementation phases complete

### Parallel Opportunities

- **T002 + T003**: Same file, must be sequential
- **T004 + T005**: Different files, can run in parallel [P]
- **T006 + T007**: Same file, must be sequential
- **T008 + T009**: Different files, can run in parallel [P]
- **T011 + T012**: Same file, must be sequential
- **Phase 3 + Phase 4 + Phase 7**: Different files/layers, can run in parallel

---

## Parallel Example: Backend Changes

```bash
# Phase 3 and Phase 4 can run in parallel (different files):
# Backend REST (ReleaseController.kt):
Task: T002 + T003 — Update @Secured on create/delete endpoints

# MCP Tools (two different files):
Task: T004 — Update CreateReleaseTool.kt
Task: T005 — Update DeleteReleaseTool.kt
```

---

## Implementation Strategy

### MVP First (User Stories 1+2+3 — Backend Only)

1. Complete T001 (foundational isReqAdmin)
2. Complete T002 + T003 (REST endpoint @Secured changes)
3. Complete T004 + T005 (MCP tool role checks)
4. Run `./gradlew build` to verify
5. **STOP and VALIDATE**: Backend authorization is now correct

### Incremental Delivery

1. Backend authorization (T001-T005) → Build passes → Core feature working
2. Frontend UI (T006-T009) → Visual enforcement added
3. E2E test (T010) → Automated validation
4. Documentation (T011-T013, T015) → External-facing accuracy
5. Final build (T014) → Ship-ready

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- US1 and US2 are merged into Phase 3 since both involve the same file (ReleaseController.kt)
- No database migrations needed — authorization-only changes
- ReleaseStatusActions.tsx is intentionally NOT modified (RELEASE_MANAGER keeps status management)
- ReleaseDetail.tsx inherits changes via canDeleteRelease() from permissions.ts — no direct modification needed
