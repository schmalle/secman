# Quickstart: REQADMIN Role for Release Management

**Feature**: 079-reqadmin-release-role
**Date**: 2026-02-06

## Implementation Sequence

### Step 1: Backend — ReleaseController.kt

Change `@Secured` annotations on create and delete endpoints:

**File**: `src/backendng/src/main/kotlin/com/secman/controller/ReleaseController.kt`

- Line ~35: `@Secured("ADMIN", "RELEASE_MANAGER")` → `@Secured("ADMIN", "REQADMIN")` (createRelease)
- Line ~136: `@Secured("ADMIN", "RELEASE_MANAGER")` → `@Secured("ADMIN", "REQADMIN")` (deleteRelease)
- Leave `updateReleaseStatus` (line ~155) unchanged as `@Secured("ADMIN", "RELEASE_MANAGER")`

### Step 2: Backend — MCP Tools

**File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateReleaseTool.kt`
- Change `!userRoles.contains("RELEASE_MANAGER")` to `!userRoles.contains("REQADMIN")`
- Update error message to "ADMIN or REQADMIN role required"

**File**: `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteReleaseTool.kt`
- Same change as CreateReleaseTool

### Step 3: Frontend — permissions.ts

**File**: `src/frontend/src/utils/permissions.ts`

1. Add `isReqAdmin()` function after `isReleaseManager()`:
   ```typescript
   export function isReqAdmin(roles: string[] | undefined): boolean {
     if (!roles || !Array.isArray(roles)) return false;
     return roles.includes('REQADMIN');
   }
   ```

2. Update `canCreateRelease()`: Replace `isReleaseManager(roles)` with `isReqAdmin(roles)`

3. Update `canDeleteRelease()`: Replace RELEASE_MANAGER ownership check with REQADMIN ownership check

### Step 4: Frontend — Components

**File**: `src/frontend/src/components/ReleaseList.tsx`
- Line ~71: Change `hasRole('RELEASE_MANAGER')` to `hasRole('REQADMIN')` in `canCreate`

**File**: `src/frontend/src/components/ReleaseManagement.tsx`
- Add role check for create button visibility (currently missing)

### Step 5: Documentation — MCP.md

**File**: `docs/MCP.md`
- Update create_release and delete_release tool descriptions: "ADMIN or RELEASE_MANAGER" → "ADMIN or REQADMIN"
- Keep list/get/set_release_status/compare tools unchanged

### Step 6: E2E Test Script

**File**: `scripts/release-e2e-test.sh`
- New bash script using SECMAN_USERNAME, SECMAN_PASSWORD, SECMAN_API_KEY env vars
- Tests: create, delete, lifecycle, MCP tools, exports
- Dependencies: curl, jq

### Step 7: Build & Verify

```bash
./gradlew build
```

## Verification Checklist

- [ ] `./gradlew build` passes
- [ ] REQADMIN user can create release via REST API (HTTP 201)
- [ ] REQADMIN user can delete release via REST API (HTTP 200)
- [ ] RELEASE_MANAGER user gets HTTP 403 on create/delete
- [ ] RELEASE_MANAGER user can still update status (HTTP 200)
- [ ] MCP create_release works with REQADMIN delegation
- [ ] MCP delete_release works with REQADMIN delegation
- [ ] MCP create_release rejects RELEASE_MANAGER delegation
- [ ] Frontend shows create button for REQADMIN users
- [ ] Frontend hides create button for RELEASE_MANAGER users
- [ ] E2E test script passes all tests
- [ ] MCP.md reflects correct roles
